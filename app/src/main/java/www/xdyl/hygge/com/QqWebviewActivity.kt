package www.xdyl.hygge.com

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import www.xdyl.hygge.com.databinding.ActivityQqWebviewBinding

/**
 * QQ 登录内置 WebView 授权页：
 * 打开 graph.qq.com 授权 → 用户点授权 → 服务端 callback 绑定会话 →
 * 同时后台轮询 check-qq-login，成功即关闭本页。
 */
class QqWebviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQqWebviewBinding
    private lateinit var api: ApiClient
    private var pollJob: Job? = null
    private var sessionId: String = ""

    companion object {
        /** 供 MainActivity 传参 */
        const val EXTRA_URL = "auth_url"
        const val EXTRA_SESSION = "session_id"
        /** 授权完成的结果码（MainActivity 用 onActivityResult / registerForActivityResult 接） */
        const val RESULT_LOGGED_IN = 77
        const val RESULT_FAILED = 78
        /** 需要账号密码完成 QQ 绑定式登录（服务端返回 temp_token） */
        const val RESULT_NEED_BIND = 79
        const val EXTRA_TEMP_TOKEN = "temp_token"
        const val EXTRA_QQ_NICKNAME = "qq_nickname"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQqWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        api = ApiClient(SessionStore(this))
        sessionId = intent.getStringExtra(EXTRA_SESSION) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""

        if (url.isBlank() || sessionId.isBlank()) {
            finishWith(RESULT_FAILED)
            return
        }

        binding.btnQqCancel.setOnClickListener {
            pollJob?.cancel()
            finishWith(RESULT_FAILED)
        }

        binding.webview.settings.javaScriptEnabled = true
        binding.webview.settings.domStorageEnabled = true
        // 关键：使用桌面 UA —— 让 QQ 授权页走"扫码/网页登录"，
        // 避免触发 wtloginmqq:// 快速登录被系统浏览器截走整个授权流程（token 会绑到浏览器会话）。
        binding.webview.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: ""
                LogManager.log("[QQ-WebView] navigate: $u")
                return when {
                    // QQ 快速登录协议：不再丢给系统（那会把授权流程交给浏览器）。
                    // 桌面 UA 下正常不会出现；出现也拦下并提示扫码登录。
                    scheme == "wtloginmqq" || scheme == "mqq" || scheme == "mqqopensdkapi" -> {
                        LogManager.log("[QQ-WebView] 拦截快速登录协议，保持页内扫码登录")
                        Toast.makeText(
                            this@QqWebviewActivity,
                            "请使用页面内的二维码/账号登录完成授权",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                    // http/https 照常由 WebView 加载
                    scheme == "http" || scheme == "https" -> false
                    else -> true // 其他自定义 scheme一律拦截
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 授权回调页出现 → 说明服务端即将完成换票，立即补一次轮询加速
                if (url.contains("callback", ignoreCase = true)) {
                    LogManager.log("[QQ-WebView] 到达回调页: $url")
                    lifecycleScope.launch {
                        try {
                            when (val r = withContext(Dispatchers.IO) { api.pollQQDetail(sessionId) }) {
                                is ApiClient.QQPoll.Token -> {
                                    binding.tvQqTitle.text = "登录成功，正在返回…"
                                    finishWith(RESULT_LOGGED_IN)
                                }
                                is ApiClient.QQPoll.NeedBind -> {
                                    binding.tvQqTitle.text = "授权完成，正在绑定账号…"
                                    setResult(
                                        RESULT_NEED_BIND,
                                        android.content.Intent()
                                            .putExtra(EXTRA_TEMP_TOKEN, r.tempToken)
                                            .putExtra(EXTRA_QQ_NICKNAME, r.nickname)
                                    )
                                    finish()
                                }
                                else -> { }
                            }
                        } catch (e: Exception) {
                            LogManager.log("[QQ-WebView] 回调页轮询异常: ${e.message}")
                        }
                    }
                }
            }
        }
        binding.webview.loadUrl(url)

        startPolling()
    }

    private fun startPolling() {
        pollJob = lifecycleScope.launch {
            for (i in 0 until 45) { // 45 × 2s = 90s
                delay(2000)
                val outcome = try {
                    withContext(Dispatchers.IO) { api.pollQQDetail(sessionId) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.tvQqTitle.text = (e.message ?: "登录失败")
                    }
                    delay(1500)
                    finishWith(RESULT_FAILED)
                    return@launch
                }
                when (outcome) {
                    is ApiClient.QQPoll.Token -> {
                        withContext(Dispatchers.Main) {
                            binding.tvQqTitle.text = "登录成功，正在返回…"
                            finishWith(RESULT_LOGGED_IN)
                        }
                        return@launch
                    }
                    is ApiClient.QQPoll.NeedBind -> {
                        withContext(Dispatchers.Main) {
                            binding.tvQqTitle.text = "授权完成，正在绑定账号…"
                            val data = android.content.Intent()
                                .putExtra(EXTRA_TEMP_TOKEN, outcome.tempToken)
                                .putExtra(EXTRA_QQ_NICKNAME, outcome.nickname)
                            setResult(RESULT_NEED_BIND, data)
                            finish()
                        }
                        return@launch
                    }
                    else -> {
                        // 继续等待
                    }
                }
                if (i % 5 == 4) {
                    withContext(Dispatchers.Main) {
                        binding.tvQqTitle.text = "等待授权确认…（${(45 - i - 1) * 2}秒）"
                    }
                }
            }
            withContext(Dispatchers.Main) { finishWith(RESULT_FAILED) }
        }
    }

    private fun finishWith(code: Int) {
        setResult(code)
        finish()
    }

    override fun onBackPressed() {
        if (binding.webview.canGoBack()) binding.webview.goBack()
        else { pollJob?.cancel(); finishWith(RESULT_FAILED) }
    }
}