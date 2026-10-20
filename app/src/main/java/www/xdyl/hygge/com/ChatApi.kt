package www.xdyl.hygge.com

import android.content.Context
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 【私聊网络层】
 *
 * 服务端接口（兼容 legacy 与 v2 中继）：
 *   GET  /chat/ping                                     健康检查
 *   POST /chat/key        Bearer {userId, publicKey}     登记本机公钥
 *   GET  /chat/key/{uid}                                 取对端公钥（匿名）
 *   POST /chat/send       Bearer {from,to,msgId,cipher,ts}  发送密文（强幂等）
 *   GET  /chat/pull       Bearer ?cursor=&limit=         按 seq 增量拉取（含自己发的）
 *   POST /chat/read       Bearer {peerId, upto}          已读水位
 *
 * 认证：需要身份的请求都带 `Authorization: Bearer <主站 access_token>`。
 * 服务端用该令牌回调主站 /user/profile 校验并取出 username，不落库。
 *
 * 【端点自动切换】
 * 部分网络会对 Cloudflare 的 443 端口做间歇性 RST（TLS 握手直接被打断），
 * 而同一域名的 8443 / 2053 端口（Cloudflare 同样支持 HTTPS 代理）往往是通的。
 * 所以这里不是"一个地址打通到底"，而是按下面的顺序逐个尝试，成功即停：
 *
 *   https://host          →  https://host:8443  →  https://host:2053  →  http://host
 *
 * 其中 http 只作最后兜底（新版中继会直接回 400 HTTPS_REQUIRED）。
 */
object ChatApi {

    const val PREF_SERVER = "chat_server"

    /**
     * 平台路径前缀。
     *
     * 服务端按平台把入口分开：
     *   Android → /Android/chat/…        （legacy 接口，本客户端使用）
     *   iOS     → /iOS/chat/v2/…         （设备密钥 / 回执 / Bark 等 iOS 侧能力）
     * 服务端会把前缀剥掉后按同一套路由处理，所以两边互不干扰。
     */
    const val PLATFORM_PREFIX = "/Android"

    /** Cronet 单次请求超时（ms） */
    private const val CRONET_TIMEOUT_MS = 12000L

    /** 单页请求条数 */
    const val PAGE_SIZE = 200

    /** 备用 HTTPS 端口（Cloudflare 支持代理的端口） */
    private val FALLBACK_HTTPS_PORTS = listOf(8443, 2053)

    /**
     * 用浏览器 UA 发请求。
     *
     * 部分校园网 / 运营商会按 User-Agent 掐断连接（对 okhttp、python-requests 这类
     * "非浏览器"标记直接 RST）。实测同一台手机上：
     *   curl（UA=curl/7.x）        → 200，稳定
     *   OkHttp（UA=okhttp/4.x）    → 每次都在 TLS/请求初期被 reset
     * 因此这里伪装成 Chrome，避免被 UA 规则命中。
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; SPN-AL00) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val uaInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Connection", "keep-alive")
            .build()
        chain.proceed(req)
    }

    /**
     * TLS 指纹降级。
     *
     * 现象：同一台手机、同一网络、同一 IP，
     *   curl（BoringSSL, TLS1.3 + h2）        → 200
     *   OkHttp（Conscrypt, TLS1.2/1.3 + h2）  → 每次 TLS 握手被 RST
     * 唯一的差别是 ClientHello 本身。这里把握手参数退到最保守的形态
     * （TLS1.2 + 宽松 cipher + 只用 HTTP/1.1），让指纹远离被拦的特征。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(NetDns)
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS).build(),
                ConnectionSpec.CLEARTEXT
            )
        )
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .addInterceptor(uaInterceptor)
        .build()

    fun serverBase(context: Context): String {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SERVER, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun isConfigured(context: Context): Boolean = serverBase(context).isNotBlank()

    private fun authOf(context: Context): String =
        try {
            SessionStore(context).accessToken
        } catch (e: Exception) {
            ""
        }

    private const val PREF_SCHEME = "chat_scheme"

    private fun prefScheme(context: Context): String =
        try {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .getString(PREF_SCHEME, "") ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun rememberScheme(context: Context, url: String) {
        val scheme = if (url.startsWith("https://", ignoreCase = true)) "https" else "http"
        try {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit().putString(PREF_SCHEME, scheme).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * 候选端点列表（按优先级）。
     *
     * 默认明文优先：实测这条网络对 Cloudflare 的 TLS 连接（443/8443/2053，
     * 不管 Conscrypt 还是 BoringSSL）一律 RST，而明文能正常到达。
     * 聊天正文本身是端到端加密的（X25519 + AES-GCM），明文链路只暴露元数据。
     *
     * 上次成功的协议会被记住并优先使用，所以换到允许 TLS 的网络后会自动恢复走 HTTPS。
     */
    private fun endpoints(base: String, prefer: String = ""): List<String> {
        val b = base.trimEnd('/')
        val m = Regex("""^([A-Za-z]+)://([^/:]+)(:\d+)?(/.*)?$""").find(b)
        if (m == null) return listOf(b)

        val host = m.groupValues[2]
        val port = m.groupValues[3]
        val rest = m.groupValues[4]
        // 用户自己填了端口：不做扩展
        if (port.isNotBlank()) return listOf(b)

        val plain = "http://" + host + rest
        val tls = "https://" + host + rest
        val tlsPorts = FALLBACK_HTTPS_PORTS.map { "https://" + host + ":" + it + rest }

        return if (prefer == "https") {
            (listOf(tls) + tlsPorts + listOf(plain)).distinct()
        } else {
            (listOf(plain) + listOf(tls) + tlsPorts).distinct()
        }
    }


    @Volatile
    private var echProbed = false

    /** 首次请求时后台探测一次：这台网络能否拿到 ECHConfig（决定内置 ECH 是否可行） */
    private fun probeEchOnce(context: Context) {
        if (echProbed) return
        echProbed = true
        Thread {
            try {
                val base = serverBase(context)
                val host = base.removePrefix("https://").removePrefix("http://")
                    .substringBefore('/').substringBefore(':')
                if (host.isBlank()) return@Thread
                val info = EchProbe.fetch(host)
                if (info != null) {
                    LogManager.i("[ECH] 探测成功：${info.echLen} 字节，DNS=${info.dns}，outer=${info.outerName}")
                } else {
                    LogManager.w("[ECH] 探测失败：拿不到 ECHConfig，内置 ECH 不可行")
                }
            } catch (e: Exception) {
                LogManager.w("[ECH] 探测异常：${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 统一请求入口。
     *
     * 传输层优先级：
     *   1. Cronet（Chrome 的网络栈 / BoringSSL）——避开按 TLS 指纹的选择性拦截
     *   2. OkHttp（系统 TLS）——Cronet 不可用时兜底
     * 两者都不通就换下一个端点（443 → 8443 → 2053 → http）。
     */
    private fun call(context: Context, method: String, path: String, body: JSONObject?): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        probeEchOnce(context)
        val auth = authOf(context)
        val jsonType = "application/json".toMediaType()
        val list = endpoints(base, prefScheme(context))

        val payload = if (method == "GET") null else (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
        val httpHeaders = LinkedHashMap<String, String>()
        httpHeaders["User-Agent"] = BROWSER_UA
        httpHeaders["Accept"] = "application/json, text/plain, */*"
        httpHeaders["Accept-Language"] = "zh-CN,zh;q=0.9"
        if (auth.isNotBlank()) httpHeaders["Authorization"] = "Bearer " + auth
        if (payload != null) httpHeaders["Content-Type"] = "application/json"

        val cronetReady = CronetHttp.ensure(context)

        for ((idx, ep) in list.withIndex()) {
            val url = ep + PLATFORM_PREFIX + path
            var code = -1
            var text = ""
            var via = "okhttp"
            var done = false

            if (cronetReady && url.startsWith("https://", ignoreCase = true)) {
                val r = CronetHttp.request(url, method, httpHeaders, payload, CRONET_TIMEOUT_MS)
                if (r != null) {
                    code = r.code
                    text = r.body
                    via = "cronet"
                    done = true
                }
            }

            if (!done) {
                try {
                    val rb = Request.Builder().url(url)
                    httpHeaders.forEach { (k, v) -> rb.header(k, v) }
                    if (method == "GET") {
                        rb.get()
                    } else {
                        rb.post((payload ?: ByteArray(0)).toRequestBody(jsonType))
                    }
                    client.newCall(rb.build()).execute().use { resp ->
                        code = resp.code
                        text = resp.body?.string() ?: ""
                    }
                } catch (e: Exception) {
                    LogManager.w("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " @" + shortEp(ep) + " 失败: " + e.message)
                    continue
                }
            }

            if (code in 200..299) {
                rememberScheme(context, url)
                if (idx > 0) {
                    LogManager.i("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " 已切换到备用端点 " + shortEp(ep) + "（" + via + "）")
                } else {
                    LogManager.d("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " OK（" + via + "）")
                }
                return try {
                    JSONObject(text)
                } catch (e: Exception) {
                    LogManager.e("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " 返回非 JSON: " + text.take(120))
                    null
                }
            }

            LogManager.w("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " @" + shortEp(ep) + "（" + via + "）-> " + code + " " + text.take(160))

            if (code == 400 && text.contains("HTTPS_REQUIRED")) continue
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 409) return null
        }

        LogManager.e("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " 所有端点均失败（" + list.size + " 个）")
        return null
    }

    private fun shortEp(ep: String): String =
        ep.substringAfter("://").let { if (it.length <= 26) it else it.take(26) + "…" }

    private fun get(context: Context, path: String): JSONObject? = call(context, "GET", path, null)

    private fun post(context: Context, path: String, body: JSONObject): JSONObject? =
        call(context, "POST", path, body)

    /** 登记本机公钥（身份来自 Bearer 头） */
    fun uploadKey(context: Context, userId: String, publicKey: String): Boolean {
        val r = post(context, "/chat/key", JSONObject()
            .put("userId", userId)
            .put("publicKey", publicKey)) ?: return false
        return r.optBoolean("ok", false) || r.optInt("code", 0) == 200
    }

    /** 取对端公钥（null = 用户不存在 / 未绑定 / 网络失败） */
    fun fetchPeerKey(context: Context, userId: String): String? {
        val r = get(context, "/chat/key/$userId") ?: return null
        val data = r.optJSONObject("data") ?: r
        return data.optString("publicKey", "").ifBlank { null }
    }

    /**
     * 按 username 查主站公开资料（直连主站，不走中继）
     * GET /player/info/{player}
     */
    fun fetchPlayerName(base: String, player: String): Pair<String, Boolean>? {
        if (base.isBlank() || player.isBlank()) return null
        val encoded = android.net.Uri.encode(player)
        for (ep in endpoints(base)) {
            try {
                val req = Request.Builder().url("$ep/player/info/$encoded").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val data = JSONObject(body).optJSONObject("data") ?: return@use
                    val registered = data.optBoolean("registered", false)
                    val name = data.optString("nickname", "")
                        .ifBlank { data.optString("username", "") }
                        .ifBlank { player }
                    LogManager.d("[CHAT] /player/info/$player -> $name (registered=$registered)")
                    return name to registered
                }
            } catch (e: Exception) {
                LogManager.w("[CHAT] /player/info/$player @${shortEp(ep)} 失败: ${e.message}")
            }
        }
        return null
    }

    /** 发送密文（服务端按 (from, msgId) 幂等） */
    fun send(
        context: Context,
        from: String,
        to: String,
        msgId: String,
        cipher: String,
        ts: Long
    ): Boolean {
        val r = post(context, "/chat/send", JSONObject()
            .put("from", from)
            .put("to", to)
            .put("msgId", msgId)
            .put("cipher", cipher)
            .put("ts", ts)) ?: return false
        return r.optBoolean("ok", false) || r.optInt("code", 0) == 200
    }

    /** 一页拉取结果 */
    data class PullResult(
        val items: List<JSONObject>,
        val nextCursor: Long,
        val hasMore: Boolean
    )

    /**
     * 按服务端 seq 游标拉一页。null = 请求失败。
     * 空列表 + hasMore=false 是合法结果。
     */
    fun pullPage(context: Context, cursor: Long, limit: Int = PAGE_SIZE): PullResult? {
        val r = get(context, "/chat/pull?cursor=$cursor&limit=$limit") ?: return null
        val arr = r.optJSONArray("data") ?: r.optJSONArray("messages") ?: return null
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        val next = r.optLong("next_cursor", cursor)
        val hasMore = r.optBoolean("has_more", false)
        return PullResult(out, if (next > 0) next else cursor, hasMore)
    }

    /** 兼容旧调用 */
    fun pull(context: Context, cursor: Long): List<JSONObject> =
        pullPage(context, cursor)?.items ?: emptyList()

    /** 标记已读（服务端存单调水位） */
    fun markRead(context: Context, peerId: String, upto: Long) {
        post(context, "/chat/read", JSONObject()
            .put("peerId", peerId)
            .put("upto", upto))
    }
}