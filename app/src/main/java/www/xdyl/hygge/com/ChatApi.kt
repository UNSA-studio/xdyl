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
 * 服务端接口约定（服务器只转发密文，无法解密）：
 *   POST /chat/key        {userId, token, publicKey}     上传本机公钥
 *   GET  /chat/key/{uid}                                 取对端公钥
 *   POST /chat/send       {from, to, msgId, cipher, ts}  发送密文
 *   GET  /chat/pull?since={ms}                           拉取新消息
 *   POST /chat/read       {peerId, upto}                 标记已读
 *
 * 服务器地址在设置里可配（pref: chat_server），默认读 BuildConfig 常量。
 * 未配置时所有调用返回错误，UI 会提示"私聊服务器未配置"。
 */
object ChatApi {

    const val PREF_SERVER = "chat_server"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(NetDns)
        .build()

    fun serverBase(context: Context): String {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SERVER, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun isConfigured(context: Context): Boolean = serverBase(context).isNotBlank()

    /**
     * HTTPS 连接被重置时自动回退到 HTTP（部分网络会 RST 掉带明文 SNI 的 TLS 握手）。
     * 返回 "http://host[/prefix]"；不是 https 时原样返回。
     */
    private fun httpFallback(base: String): String? {
        if (!base.startsWith("https://", ignoreCase = true)) return null
        return "http://" + base.substring("https://".length)
    }

    private fun get(context: Context, path: String): JSONObject? {
        val base = serverBase(context)
        if (base.isBlank()) return null
        return try {
            val req = Request.Builder().url("$base$path").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    LogManager.w("[CHAT] GET $path -> ${resp.code} $body")
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
                val req = Request.Builder().url("$fb$path").get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return null
                    if (!resp.isSuccessful) {
                        LogManager.w("[CHAT] GET(http) $path -> ${resp.code} $body")
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
        return try {
            val req = Request.Builder()
                .url("$base$path")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    LogManager.w("[CHAT] POST $path -> ${resp.code} $text")
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
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: return null
                    if (!resp.isSuccessful) {
                        LogManager.w("[CHAT] POST(http) $path -> ${resp.code} $text")
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

    /** 上传本机公钥 */
    fun uploadKey(context: Context, userId: String, token: String, publicKey: String): Boolean {
        val r = post(context, "/chat/key", JSONObject()
            .put("userId", userId)
            .put("token", token)
            .put("publicKey", publicKey)) ?: return false
        return r.optBoolean("ok", false) || r.optInt("code", 0) == 200
    }

    /** 取对端公钥（null = 用户不存在） */
    fun fetchPeerKey(context: Context, userId: String): String? {
        val r = get(context, "/chat/key/$userId") ?: return null
        val data = r.optJSONObject("data") ?: r
        return data.optString("publicKey", "").ifBlank { null }
    }

    /**
     * 按玩家标识（user_id 或用户名）查公开资料
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

    /** 发送密文 */
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

    /** 拉取 since 之后的消息 */
    fun pull(context: Context, since: Long): List<JSONObject> {
        val r = get(context, "/chat/pull?since=$since") ?: return emptyList()
        val arr = r.optJSONArray("data") ?: r.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    /** 标记已读 */
    fun markRead(context: Context, peerId: Int, upto: Long) {
        post(context, "/chat/read", JSONObject()
            .put("peerId", peerId)
            .put("upto", upto))
    }
}