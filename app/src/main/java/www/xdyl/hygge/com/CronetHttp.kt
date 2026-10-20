package www.xdyl.hygge.com

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 内置 Chromium 网络栈（Cronet → BoringSSL）。
 *
 * 为什么需要它：
 *
 *   Android 系统自带的 TLS 实现是 Conscrypt，它发出的 ClientHello（TLS 指纹）
 *   在部分校园网 / 运营商线路会被直接 RST：
 *
 *     curl（BoringSSL，TLS1.3 + h2）          → 200
 *     Chrome（BoringSSL，ECH 开启）           → 200
 *     OkHttp（Conscrypt，TLS1.2 + HTTP/1.1）  → SSL handshake aborted / reset
 *
 *   同一个 IP、同一毫秒、同样的 UA 和端口都试过，差别只在 ClientHello 本身。
 *   这类拦截没有服务端开关可关，只能换客户端 TLS 实现。
 *
 *   Cronet 就是 Chrome 用的网络栈（BoringSSL + QUIC + h2），把它打进 APK 后，
 *   App 发出的握手与 Chrome 一致，从而绕过按指纹的选择性拦截。
 *
 * 说明：
 *   - 引擎全局单例，初始化失败（缺 SO / ABI 不支持）时 [ensure] 返回 false，
 *     调用方自动回退到 OkHttp，不会让私聊直接不可用。
 *   - 请求是同步封装（内部 CountDownLatch），因为调用方本来就在工作线程。
 */
object CronetHttp {

    data class Resp(val code: Int, val body: String)

    @Volatile
    private var engine: CronetEngine? = null

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "cronet-io").apply { isDaemon = true }
    }

    /** 懒初始化；返回是否可用 */
    fun ensure(context: Context): Boolean {
        if (engine != null) return true
        synchronized(this) {
            if (engine != null) return true
            engine = try {
                CronetEngine.Builder(context.applicationContext)
                    .enableHttp2(true)
                    .enableQuic(false)
                    .enableBrotli(true)
                    .build()
                    .also { LogManager.i("[CHAT] Cronet(BoringSSL) 引擎已就绪") }
            } catch (e: Throwable) {
                LogManager.e("[CHAT] Cronet 初始化失败，回退系统 TLS：${e.message}")
                null
            }
            return engine != null
        }
    }

    fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        timeoutMs: Long
    ): Resp? {
        val e = engine ?: return null

        val latch = CountDownLatch(1)
        var code = 0
        val sb = StringBuilder()
        var error: String? = null

        val cb = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String
            ) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                code = info.httpStatusCode
                request.read(ByteBuffer.allocateDirect(16 * 1024))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                buffer: ByteBuffer
            ) {
                buffer.flip()
                val n = buffer.remaining()
                if (n > 0) {
                    val bytes = ByteArray(n)
                    buffer.get(bytes)
                    sb.append(String(bytes, Charsets.UTF_8))
                }
                buffer.clear()
                if (n > 0) {
                    request.read(buffer)
                } else {
                    latch.countDown()
                }
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                latch.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, ex: CronetException?) {
                error = ex?.message ?: "failed"
                latch.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                error = "canceled"
                latch.countDown()
            }
        }

        val req = try {
            val b = e.newUrlRequestBuilder(url, cb, executor)
            headers.forEach { (k, v) -> b.addHeader(k, v) }
            b.setHttpMethod(method)
            if (bodyBytes != null) {
                b.setUploadDataProvider(UploadDataProviders.create(bodyBytes), executor)
            }
            b.build()
        } catch (t: Throwable) {
            LogManager.e("[CHAT] Cronet 构建请求失败：${t.message}")
            return null
        }

        req.start()

        val done = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!done) {
            try {
                req.cancel()
            } catch (_: Throwable) {
            }
            return null
        }
        if (error != null || code == 0) {
            LogManager.w("[CHAT] Cronet 请求失败：$error")
            return null
        }
        return Resp(code, sb.toString())
    }
}