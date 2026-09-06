package www.xdyl.hygge.com

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
                LogManager.log("[QQ-WebView] navigate: $u")
                // 授权完成后 QQ 会 302 到 redirect_uri（callback.html→qq-callback）。
                // 我们让 WebView 照常加载，由轮询确认结果。
                return false
            }
        }
        binding.webview.loadUrl(url)

        startPolling()
    }

    private fun startPolling() {
        pollJob = lifecycleScope.launch {
            for (i in 0 until 45) { // 45 × 2s = 90s
                delay(2000)
                val ok = withContext(Dispatchers.IO) { runCatching { api.pollQQLogin(sessionId) }.getOrDefault(false) }
                if (ok) {
                    withContext(Dispatchers.Main) {
                        binding.tvQqTitle.text = "✅ 登录成功，正在返回…"
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