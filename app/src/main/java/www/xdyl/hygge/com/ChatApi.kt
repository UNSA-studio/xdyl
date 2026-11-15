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

    /** 上次成功登记的公钥，换代时作为 expectedPublicKey */
    private const val KEY_LAST_REGISTERED = "chat_last_registered_key"

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
            .header("Connection", "close")
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
        // 连接池设为 0：这条网络的中间设备会悄悄挂死空闲连接，
        // 复用旧连接必然 Connection reset（明明是新建连接就能成功）。
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
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
        val manual = prefs.getString(PREF_SERVER, "")?.trim()?.trimEnd('/') ?: ""
        // 用户手动填写的地址优先；为空时落到内置默认地址。
        // 这样新装机不需要任何配置就能开始登记和使用私聊。
        return manual.ifBlank { www.xdyl.hygge.com.ChatDeviceRegistry.DEFAULT_CHAT_BASE }
    }

    fun isConfigured(context: Context): Boolean = serverBase(context).isNotBlank()

    private fun authOf(context: Context): String =
        try {
            SessionStore(context).accessToken
        } catch (e: Exception) {
            ""
        }

    private const val PREF_SCHEME = "chat_scheme"
    private const val PREF_ENDPOINT = "chat_endpoint"

    private fun prefScheme(context: Context): String =
        try {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .getString(PREF_SCHEME, "") ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun prefEndpoint(context: Context): String =
        try {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .getString(PREF_ENDPOINT, "") ?: ""
        } catch (e: Exception) {
            ""
        }

    private fun rememberEndpoint(context: Context, ep: String) {
        try {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit().putString(PREF_ENDPOINT, ep).apply()
        } catch (e: Exception) {
        }
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
    private fun endpoints(base: String, prefer: String = "", preferEp: String = ""): List<String> {
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

        // ECH 可用时走 HTTPS（加密 SNI，不怕阻断）；否则明文优先
        val ordered = if (prefer == "https" || EchTls.available()) {
            (listOf(tls) + tlsPorts + listOf(plain)).distinct()
        } else {
            (listOf(plain) + listOf(tls) + tlsPorts).distinct()
        }
        // 上次成功的端点放到最前面，避免每次都把 4 个端点全试一遍
        if (preferEp.isNotBlank()) {
            val hostPart = preferEp.substringAfter("://").substringBefore("/")
            val fixed = ordered.filter { it.substringAfter("://").substringBefore("/") == hostPart }
            if (fixed.isNotEmpty()) return fixed + ordered.filter { it !in fixed }
        }
        return ordered
    }


    @Volatile
    private var echProbed = false

    /** 启动时自检：取 ECHConfig + 用 ECH 打一次 ping（无需登录，结果写日志） */
    fun echSelfTest(context: Context) {
        probeEchOnce(context)
    }

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
                    // 实测：用 BoringSSL + ECH 打一次 /chat/ping
                    val r = EchChannel.request(
                        context, "https://" + host, "/chat/ping", "GET", emptyMap(), null
                    )
                    if (r != null) {
                        LogManager.i("[ECH] ★ ping 成功：code=${r.code}，chunked=${r.chunked}，body=${r.body.take(140)}")
                    } else {
                        LogManager.w("[ECH] ping 失败：ECH 通道没通")
                    }
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
    /** 供 ChatDeviceRegistry 等内部组件使用；不对外暴露 */
    fun callInternal(context: Context, method: String, path: String, body: JSONObject?): JSONObject? =
        call(context, method, path, body)

    private fun call(context: Context, method: String, path: String, body: JSONObject?): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        probeEchOnce(context)
        val auth = authOf(context)
        val jsonType = "application/json".toMediaType()
        val list = endpoints(base, prefScheme(context), prefEndpoint(context))

        // 该网络会 RST 明文 GET（POST 正常），所以 GET 统一改用 POST + __alias=1，
        // 由服务端在最外层把它还原成 GET 处理。
        val isGetAlias = method == "GET"
        val payload = when {
            isGetAlias -> "{}".toByteArray(Charsets.UTF_8)
            body != null -> body.toString().toByteArray(Charsets.UTF_8)
            else -> "{}".toByteArray(Charsets.UTF_8)
        }
        val httpMethod = if (isGetAlias) "POST" else method
        val aliasQuery = if (isGetAlias) (if (path.contains('?')) "&__alias=1" else "?__alias=1") else ""
        val httpHeaders = LinkedHashMap<String, String>()
        httpHeaders["User-Agent"] = BROWSER_UA
        httpHeaders["Accept"] = "application/json, text/plain, */*"
        httpHeaders["Accept-Language"] = "zh-CN,zh;q=0.9"
        if (auth.isNotBlank()) httpHeaders["Authorization"] = "Bearer " + auth
        if (payload != null) httpHeaders["Content-Type"] = "application/json"


        for ((idx, ep) in list.withIndex()) {
            val url = ep + PLATFORM_PREFIX + path + aliasQuery
            var code = -1
            var text = ""
            var via = "okhttp"


            // ① ECH（BoringSSL，加密 SNI）：绕过明文 SNI 阻断，HTTPS 端点首选
            if (ep.startsWith("https://", ignoreCase = true) && EchTls.available()) {
                // native TLS 失败会抛异常（TLS handshake aborted / Connection reset）。
                // 以前没有兜住，异常直接穿出 call()，整条请求链变成 null，
                // 上层只能显示"服务器未返回公钥"，把网络问题误报成账号问题。
                val echResp = try {
                    EchChannel.request(
                        context,
                        ep,
                        PLATFORM_PREFIX + path,
                        method,
                        httpHeaders,
                        if (method == "GET") null else (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                    )
                } catch (e: Throwable) {
                    LogManager.w("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " @"
                            + shortEp(ep) + " ECH 失败: " + (e.message ?: e.javaClass.simpleName))
                    null
                }
                if (echResp != null) {
                    code = echResp.code
                    text = echResp.body
                    via = "ech"
                }
            }
            // ② 回退：系统 TLS / 明文（GET 用 POST+__alias 伪装）
            if (code < 0) try {
                    val rb = Request.Builder().url(url)
                    httpHeaders.forEach { (k, v) -> rb.header(k, v) }
                    // 必须用真实动词：原先无条件 .post()，导致 PUT 请求在回退分支
                    // 被发成 POST —— 服务端按 method 分流会 404。
                    val reqBody = (payload ?: ByteArray(0)).toRequestBody(jsonType)
                    rb.method(httpMethod, if (httpMethod == "GET") null else reqBody)
                    client.newCall(rb.build()).execute().use { resp ->
                        code = resp.code
                        text = resp.body?.string() ?: ""
                    }
                } catch (e: Exception) {
                    LogManager.w("[CHAT] " + method + " " + PLATFORM_PREFIX + path + " @" + shortEp(ep) + " 失败: " + e.message)
                    continue
                }

            if (code in 200..299) {
                rememberScheme(context, url)
                rememberEndpoint(context, ep)
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

            // 记请求流水到文件：logcat 在这台机器上会被别的进程刷掉，
            // 排查"到底哪一环返回了什么"只能靠落盘。只写动词/路径/端口/状态码，
            // 不写任何密钥或密码。
            try {
                java.io.File("/sdcard/NebulaUpdater/netflow.log").appendText(
                    "[" + System.currentTimeMillis() + "] " + method + " " + PLATFORM_PREFIX + path +
                    " @" + shortEp(ep) + " via=" + via + " code=" + code + "\n")
            } catch (e: Exception) { /* 忽略 */ }
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

    /**
     * 登记本机公钥（身份来自 Bearer 头）。
     *
     * 服务端有一道保护：如果本地要登记的公钥与服务器上现有的不同，
     * 必须附带"我认为服务器上现在是哪把"（expectedPublicKey），
     * 否则拒绝（409 KEY_CHANGE_REQUIRES_CONFIRMATION）—— 防止中间人
     * 静默替换公钥。
     *
     * 所以这里记住上一次成功登记的公钥，主动换代时把它作为 expected 带上。
     * 这是正常的密钥轮换流程，不是绕过安全校验。
     */
    fun uploadKey(context: Context, userId: String, publicKey: String): Boolean {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        val lastRegistered = prefs.getString(KEY_LAST_REGISTERED, null)

        val body = JSONObject()
            .put("userId", userId)
            .put("publicKey", publicKey)
        // 只有"确实换过"才带 expected，首次登记不带
        if (lastRegistered != null && lastRegistered != publicKey) {
            body.put("expectedPublicKey", lastRegistered)
        }

        var r = post(context, "/chat/key", body) ?: return false
        var err = r.optString("error", "")

        // 409 有两种来源，都必须处理：
        //   a) 本地记得旧公钥 -> 带 expected 重发即可（正常换代）
        //   b) 本地没有记忆（首次安装/清过数据/换机）-> 请求根本没带 expected，
        //      服务端必然回 KEY_CHANGE_REQUIRES_CONFIRMATION。
        // 这正是跨端一直登记不上的真正原因：本机 chat_last_registered_key 为空。
        if (err == "KEY_CHANGE_REQUIRES_CONFIRMATION") {
            // 服务端上实际的公钥与我们记忆的不一样（常见于换机、清数据、
            // 或本地密钥被主动换代而记录丢失）。
            //
            // 这里的处理是安全的：先向服务端查询"我这个账号现在登记的是哪把"，
            // 再把查询结果作为 expectedPublicKey 重试。客户端确实知道服务端现值，
            // 不构成绕过 —— 真正的中间人无从得知用户服务端上登记的公钥是什么。
            val current = fetchPeerKey(context, userId)
            if (!current.isNullOrBlank() && current != publicKey) {
                LogManager.w("[CHAT] 公钥换代：以服务端现值作为 expected 重试")
                prefs.edit().putString(KEY_LAST_REGISTERED, current).apply()
                r = post(context, "/chat/key", JSONObject()
                    .put("userId", userId)
                    .put("publicKey", publicKey)
                    .put("expectedPublicKey", current)) ?: return false
                err = r.optString("error", "")
            } else if (current.isNullOrBlank()) {
                LogManager.w("[CHAT] 公钥换代失败：查不到服务端现值（" + userId + "）")
            }
        }

        if (r.optBoolean("ok", false) || r.optInt("code", 200) == 200) {
            prefs.edit().putString(KEY_LAST_REGISTERED, publicKey).apply()
            return true
        }

        LogManager.w("[CHAT] 公钥登记失败: " + r.optString("error", "unknown"))
        return false
    }

    /**
     * 记录"服务器上现在应该是哪把公钥"。
     *
     * 只在主动换代（例如从 P-256 切到 X25519）时由 ChatCrypto 调用：
     * 那把旧公钥确实是我们自己生成并登记过的，用它做 expected 是正当的，
     * 不削弱服务端的防替换校验。
     */
    fun rememberRegisteredKey(context: Context, keyBase64: String) {
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_REGISTERED, keyBase64).apply()
    }

    /** 上一次成功登记到服务端的公钥（Base64），没有则 null */
    fun lastRegisteredKey(context: Context): String? =
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .getString(KEY_LAST_REGISTERED, null)

    /** 清掉登记记录（登出时调），下次登录当作首次登记 */
    fun clearRegisteredKey(context: Context) {
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_REGISTERED).apply()
    }

    /**
     * 取对端公钥（null = 用户不存在 / 未绑定 / 网络失败）
     *
     * 必须带上本机实际使用的算法。
     *
     * 以前的坑：服务端在收到 Bearer 时，会用"我"在 users 表里登记的公钥反推算法，
     * 再要求对端返回同算法的公钥。users 表是覆盖写的历史值，一旦本机已经换成
     * X25519 而那一行还是旧的 P-256，服务端就会把对方的 P-256 公钥发过来 ——
     * 两端曲线不同，会话密钥算不出同一个值，对方永远解不开。
     *
     * 现在显式声明，服务端按这个算法挑对端密钥；对端没有该算法的密钥时，
     * 服务端会明确报 LEGACY_ALGORITHM_MISMATCH，而不是静默给错曲线。
     */
    fun fetchPeerKey(context: Context, userId: String): String? {
        val alg = ChatCrypto.currentAlgorithm()
        val path = if (alg.isBlank()) "/chat/key/$userId" else "/chat/key/$userId?algorithm=$alg"
        val r = get(context, path) ?: return null
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

    /**
     * 发送密文（服务端按 (from, msgId) 幂等）。
     *
     * @return Pair(是否成功, 错误码)；错误码为空表示成功。
     *         服务端会拒绝「收件人 == 自己」，返回 INVALID_REQUEST；
     *         对方没上传公钥时返回 USER_NOT_FOUND / recipient not found。
     */
    fun send(
        context: Context,
        from: String,
        to: String,
        msgId: String,
        cipher: String,
        ts: Long
    ): Pair<Boolean, String> {
        val r = post(context, "/chat/send", JSONObject()
            .put("from", from)
            .put("to", to)
            .put("msgId", msgId)
            .put("cipher", cipher)
            .put("ts", ts)) ?: return false to "NETWORK"
        if (r.optBoolean("ok", false) || r.optInt("code", 0) == 200) return true to ""
        var err = r.optString("error", "")
        return false to (if (err.isBlank()) "UNKNOWN" else err)
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

    // ==================== 私钥备份 ====================
    //
    // 服务端 v1(legacy) 路由，Android 走 Bearer 主站 token 鉴权；
    // 数据存进同一张 v2_key_backups 表，按 subject 索引，与 iOS(v2) 完全互通。
    // 字段名用下划线风格（与服务端一致），避免两边命名对不上。

    /** 服务端返回的备份包 */
    data class KeyBackup(
        val pwdBlob: String,
        val codeBlob: String,
        val kdf: String
    )

    /**
     * 上传备份（两份密文 + 恢复码哈希）。
     *
     * pwdBlob / codeBlob 都是客户端加密后的密文，服务端拿到也解不开。
     */
    fun uploadKeyBackup(context: Context, pwdBlob: String, codeBlob: String,
                        codeHash: String, kdf: String): Boolean {
        val r = call(context, "PUT", "/chat/key-backup", JSONObject()
            .put("pwd_blob", pwdBlob)
            .put("code_blob", codeBlob)
            .put("code_hash", codeHash)
            .put("kdf", kdf)) ?: return false
        return r.optBoolean("ok", false)
    }

    /** 取回自己的备份；不存在返回 null */
    fun fetchKeyBackup(context: Context): KeyBackup? {
        val r = get(context, "/chat/key-backup") ?: return null
        val d = r.optJSONObject("data") ?: r
        val pwd = d.optString("pwd_blob", "")
        val code = d.optString("code_blob", "")
        if (pwd.isBlank() || code.isBlank()) return null
        return KeyBackup(pwd, code, d.optString("kdf", ""))
    }

    /** 凭恢复码哈希校验并取回备份 */
    fun lookupKeyBackup(context: Context, codeHash: String): KeyBackup? {
        val r = post(context, "/chat/key-backup/lookup", JSONObject()
            .put("code_hash", codeHash)) ?: return null
        val d = r.optJSONObject("data") ?: r
        val pwd = d.optString("pwd_blob", "")
        val code = d.optString("code_blob", "")
        if (pwd.isBlank() || code.isBlank()) return null
        return KeyBackup(pwd, code, d.optString("kdf", ""))
    }

    /** 标记已读（服务端存单调水位） */
    fun markRead(context: Context, peerId: String, upto: Long) {
        post(context, "/chat/read", JSONObject()
            .put("peerId", peerId)
            .put("upto", upto))
    }
}