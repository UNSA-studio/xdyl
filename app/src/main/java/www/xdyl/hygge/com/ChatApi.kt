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
 * 服务端接口约定（D1 版，服务器只转发密文，无法解密）：
 *   GET  /chat/ping?                                    健康检查
 *   POST /chat/key        Bearer {userId, publicKey}     上传本机公钥
 *   GET  /chat/key/{uid}                                 取对端公钥（匿名）
 *   POST /chat/send       Bearer {from,to,msgId,cipher,ts}  发送密文（强幂等）
 *   GET  /chat/pull       Bearer ?cursor=&limit=         按 seq 增量拉取（含自己发的）
 *   POST /chat/read       Bearer {peerId, upto}          已读水位
 *
 * 认证：所有需要身份的请求都带 `Authorization: Bearer <主站 access_token>`。
 * 服务端用该令牌回调 /user/profile 校验并取出 username；令牌不落服务端存储。
 *
 * 服务器地址在设置里可配（pref: chat_server），未配置时所有调用返回失败，
 * UI 会提示"私聊服务器未配置"。
 */
object ChatApi {

    const val PREF_SERVER = "chat_server"

    /** 服务端单页最大条数上限（客户端请求值会被服务端裁剪到 500 内） */
    const val PAGE_SIZE = 200

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(NetDns)
        .build()

    fun serverBase(context: Context): String {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SERVER, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun isConfigured(context: Context): Boolean = serverBase(context).isNotBlank()

    /** 取主站 access token，用作Bearer 身份。 */
    private fun authOf(context: Context): String =
        try {
            SessionStore(context).accessToken
        } catch (e: Exception) {
            ""
        }

    /**
     * HTTPS 连接被重置时自动回退到 HTTP（部分网络会 RST 掉带明文 SNI 的 TLS 握手）。
     * 返回 "http://host[/prefix]"；不是 https 时返回 null。
     */
    private fun httpFallback(base: String): String? {
        if (!base.startsWith("https://", ignoreCase = true)) return null
        return "http://" + base.substring("https://".length)
    }

    private fun Request.Builder.withAuth(context: Context): Request.Builder {
        val t = authOf(context)
        if (t.isNotBlank()) header("Authorization", "Bearer $t")
        return this
    }

    private fun get(context: Context, path: String): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        return try {
            val req = Request.Builder().url("$base$path").withAuth(context).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    LogManager.w("[CHAT] GET $path -> ${resp.code} ${body.take(200)}")
                    return null
                }
                JSONObject(body)
            }
        } catch (e: Exception) {
            LogManager.w("[CHAT] GET $path 失败: ${e.message}，尝试 HTTP 回退")
            val fb = httpFallback(base) ?: run {
                LogManager.e("[CHAT] GET $path 最终失败")
                return null
            }
            try {
                val req = Request.Builder().url("$fb$path").withAuth(context).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return null
                    if (!resp.isSuccessful) {
                        LogManager.w("[CHAT] GET(http) $path -> ${resp.code} ${body.take(200)}")
                        return null
                    }
                    LogManager.i("[CHAT] GET $path 已通过 HTTP 回退成功")
                    return JSONObject(body)
                }
            } catch (e2: Exception) {
                LogManager.e("[CHAT] GET $path HTTP 回退也失败: ${e2.message}")
                return null
            }
        }
    }

    private fun post(context: Context, path: String, body: JSONObject): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        val json = "application/json".toMediaType()
        return try {
            val req = Request.Builder()
                .url("$base$path")
                .withAuth(context)
                .post(body.toString().toRequestBody(json))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    LogManager.w("[CHAT] POST $path -> ${resp.code} ${text.take(200)}")
                    return null
                }
                JSONObject(text)
            }
        } catch (e: Exception) {
            LogManager.w("[CHAT] POST $path 失败: ${e.message}，尝试 HTTP 回退")
            val fb = httpFallback(base) ?: run {
                LogManager.e("[CHAT] POST $path 最终失败")
                return null
            }
            try {
                val req = Request.Builder()
                    .url("$fb$path")
                    .withAuth(context)
                    .post(body.toString().toRequestBody(json))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: return null
                    if (!resp.isSuccessful) {
                        LogManager.w("[CHAT] POST(http) $path -> ${resp.code} ${text.take(200)}")
                        return null
                    }
                    LogManager.i("[CHAT] POST $path 已通过 HTTP 回退成功")
                    return JSONObject(text)
                }
            } catch (e2: Exception) {
                LogManager.e("[CHAT] POST $path HTTP 回退也失败: ${e2.message}")
                return null
            }
        }
    }

    /** 上传本机公钥（身份来自 Bearer 头，服务端不接受 body 里的 token） */
    fun uploadKey(context: Context, userId: String, publicKey: String): Boolean {
        val r = post(context, "/chat/key", JSONObject()
            .put("userId", userId)
            .put("publicKey", publicKey)) ?: return false
        return r.optBoolean("ok", false) || r.optInt("code", 0) == 200
    }

    /** 取对端公钥（null = 用户不存在 / 网络失败） */
    fun fetchPeerKey(context: Context, userId: String): String? {
        val r = get(context, "/chat/key/$userId") ?: return null
        val data = r.optJSONObject("data") ?: r
        return data.optString("publicKey", "").ifBlank { null }
    }

    /**
     * 按玩家标识（username）查主站公开资料
     * GET /player/info/{player} → { username, nickname, avatar, coins, level, seconds, registered, online, banned }
     *
     * @return Pair(显示名, 是否已注册)；查不到返回 null
     */
    fun fetchPlayerName(base: String, player: String): Pair<String, Boolean>? {
        if (base.isBlank() || player.isBlank()) return null
        val url = base.trimEnd('/') + "/player/info/" + android.net.Uri.encode(player)
        val tryUrls = listOf(url).let { list ->
            val fb = httpFallback(base)
            if (fb != null) list + (fb.trimEnd('/') + "/player/info/" + android.net.Uri.encode(player)) else list
        }
        for (u in tryUrls) {
            try {
                val req = Request.Builder().url(u).get().build()
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
                LogManager.w("[CHAT] /player/info/$player 失败: ${e.message}")
            }
        }
        return null
    }

    /** 发送密文（服务端按 (from, msgId) 幂等，重试不会重复） */
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
     * 按服务端 seq 游标拉一页。返回 null 表示请求失败（网络错误 / 401 等）。
     * 注意：空列表 + hasMore=false 是合法结果，不代表失败。
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

    /** 兼容旧调用：按游标拉一页并只返回列表 */
    fun pull(context: Context, cursor: Long): List<JSONObject> =
        pullPage(context, cursor)?.items ?: emptyList()

    /** 标记已读（服务端存单调水位） */
    fun markRead(context: Context, peerId: String, upto: Long) {
        post(context, "/chat/read", JSONObject()
            .put("peerId", peerId)
            .put("upto", upto))
    }
}