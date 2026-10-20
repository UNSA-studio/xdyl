package www.xdyl.hygge.com

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

    /** 单页请求条数 */
    const val PAGE_SIZE = 200

    /** 备用 HTTPS 端口（Cloudflare 支持代理的端口） */
    private val FALLBACK_HTTPS_PORTS = listOf(8443, 2053)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(NetDns)
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

    /**
     * 生成候选端点列表（按优先级）。用户填的地址带端口时不做端口扩展。
     */
    private fun endpoints(base: String): List<String> {
        val b = base.trimEnd('/')
        val out = mutableListOf(b)
        val m = Regex("""^([A-Za-z]+)://([^/:]+)(:\d+)?(/.*)?$""").find(b)
        if (m != null) {
            val scheme = m.groupValues[1].lowercase()
            val host = m.groupValues[2]
            val port = m.groupValues[3]
            val rest = m.groupValues[4]
            if (port.isBlank()) {
                if (scheme == "https") {
                    FALLBACK_HTTPS_PORTS.forEach { out.add("https://$host:$it$rest") }
                    out.add("http://$host$rest")
                } else {
                    out.add("https://$host$rest")
                    FALLBACK_HTTPS_PORTS.forEach { out.add("https://$host:$it$rest") }
                }
            }
        }
        return out.distinct()
    }

    /**
     * 统一请求入口：按候选端点循环，遇到网络层失败换下一个。
     * 业务错误（400/401/403/404/409）视为"服务端已答复"，不再换端点重试。
     */
    private fun call(context: Context, method: String, path: String, body: JSONObject?): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        val auth = authOf(context)
        val jsonType = "application/json".toMediaType()
        val list = endpoints(base)

        for ((idx, ep) in list.withIndex()) {
            var code = -1
            var text = ""
            try {
                val rb = Request.Builder().url("$ep$path")
                if (auth.isNotBlank()) rb.header("Authorization", "Bearer $auth")
                if (method == "GET") {
                    rb.get()
                } else {
                    rb.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
                }
                client.newCall(rb.build()).execute().use { resp ->
                    code = resp.code
                    text = resp.body?.string() ?: ""
                }
            } catch (e: Exception) {
                LogManager.w("[CHAT] $method $path @${shortEp(ep)} 失败: ${e.message}")
                continue
            }

            if (code in 200..299) {
                if (idx > 0) LogManager.i("[CHAT] $method $path 已切换到备用端点 ${shortEp(ep)}")
                return try {
                    JSONObject(text)
                } catch (e: Exception) {
                    LogManager.e("[CHAT] $method $path 返回非 JSON: ${text.take(120)}")
                    null
                }
            }

            LogManager.w("[CHAT] $method $path @${shortEp(ep)} -> $code ${text.take(160)}")

            // 明文被拒（服务端强制 HTTPS）：继续尝试下一个端点
            if (code == 400 && text.contains("HTTPS_REQUIRED")) continue
            // 其它业务错误：换端点结果也一样，直接返回
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 409) return null
            // 5xx / 429：继续换端点
        }

        LogManager.e("[CHAT] $method $path 所有端点均失败（${list.size} 个）")
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