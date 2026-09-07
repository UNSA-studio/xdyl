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
        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: ""
                LogManager.log("[QQ-WebView] navigate: $u")
                return when {
                    // QQ 快速登录协议：交给系统/QQ app 处理
                    scheme == "wtloginmqq" || scheme == "mqq" || scheme == "mqqopensdkapi" -> {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u))
                        } catch (e: Exception) {
                            // 没装QQ：留在WebView用账号密码登录
                            LogManager.log("[QQ-WebView] 无QQ app，使用网页登录")
                            Toast.makeText(this@QqWebviewActivity, "未检测到QQ，请用账号密码登录", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    // http/https 照常由 WebView 加载
                    scheme == "http" || scheme == "https" -> false
                    else -> true // 其他自定义scheme一律拦截
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
                    withContext(Dispatchers.IO) { api.pollQQLogin(sessionId) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.tvQqTitle.text = (e.message ?: "登录失败")
                    }
                    delay(1500)
                    finishWith(RESULT_FAILED)
                    return@launch
                }
                if (outcome == true) {
                    withContext(Dispatchers.Main) {
                        binding.tvQqTitle.text = "登录成功，正在返回…"
                        finishWith(RESULT_LOGGED_IN)
                    }
                    return@launch
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