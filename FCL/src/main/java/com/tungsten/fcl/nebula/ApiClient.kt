package com.tungsten.fcl.nebula

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 星灯云浪服务端客户端（login.lanternwaves.fun）。
 *
 * 响应统一为 envelope {code, data, message}；本类做三层职责：
 * 1. 认证：登录拿 access/refresh token，401 时自动 refresh 并重放请求
 * 2. 社区通用访问：get/post 宽松解析（照 StarWave 的容错 envelope 设计）
 * 3. 令牌持久化：SharedPreferences（Android 侧等价物）
 */
class ApiClient(private val session: SessionStore) {

    companion object {
        const val BASE = "https://login.lanternwaves.fun"
        const val MODS_BASE = "http://api.lanternwaves.fun:5551/mods/"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = com.google.gson.Gson()

    // ==================== 认证 ====================

    suspend fun login(account: String, password: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("account", account).put("password", password)
            val root = postRaw("/login", body, requiresAuth = false)
            val data = root.getJSONObject("data")
            val access = data.getString("access_token")
            val refresh = data.optString("refresh_token", "")
            val nickname = data.optString("nickname", account)
            session.saveSession(access, refresh, nickname)
            access to refresh
        }

    /** 401 自动续期后重放；返回 true 表示已续期成功 */
    private suspend fun tryRefresh(): Boolean = withContext(Dispatchers.IO) {
        val refresh = session.refreshToken
        if (refresh.isBlank()) return@withContext false
        try {
            val body = JSONObject().put("refresh_token", refresh)
            val req = Request.Builder()
                .url("$BASE/refresh")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val root = JSONObject(resp.body!!.string())
                if (root.optInt("code") != 200) return@use false
                val data = root.getJSONObject("data")
                val newAccess = data.getString("access_token")
                val newRefresh = data.optString("refresh_token", refresh)
                session.saveSession(newAccess, newRefresh, session.username)
                true
            }
        } catch (e: Exception) {
            com.tungsten.fclcore.util.Logging.LOG.info("[API] refresh失败: ${e.message}")
            false
        }
    }

    // ==================== QQ 登录 ====================

    /** 发起 QQ 登录：返回授权页 URL（必须是 graph.qq.com 的 https 链接） */
    suspend fun startQQLogin(sessionId: String): String = withContext(Dispatchers.IO) {
        val root = execute("GET", "/qq-login?session_id=$sessionId", null, requiresAuth = false, allowRefresh = false)
        val data = root.optJSONObject("data")
        val url = data?.optString("login_url") ?: root.optString("login_url")
        if (url.isBlank() || !url.startsWith("https://graph.qq.com")) {
            throw ApiException(400, "QQ 授权地址异常")
        }
        url
    }

    /** 轮询 QQ 授权结果。返回：null=继续等待；true=成功；抛 ApiException=服务端明确失败 */
    suspend fun pollQQLogin(sessionId: String): Boolean? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/check-qq-login?session_id=$sessionId")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                com.tungsten.fclcore.util.Logging.LOG.info("[QQ] poll http=${resp.code} body=${text.take(200)}")
                val root = try { JSONObject(text) } catch (e: Exception) { return@use null }
                val code = root.optInt("code", resp.code)
                when {
                    code == 202 -> null // 等待扫码
                    code == 200 -> {
                        val data = root.optJSONObject("data") ?: root
                        val access = data.optString("access_token", data.optString("token", root.optString("access_token", "")))
                        if (access.isBlank()) {
                            com.tungsten.fclcore.util.Logging.LOG.info("[QQ] 200 但无token字段: $text")
                            null
                        } else {
                            val refresh = data.optString("refresh_token", root.optString("refresh_token", ""))
                            val nickname = data.optString("nickname", data.optString("username", root.optString("nickname", "QQ用户")))
                            session.saveSession(access, refresh, nickname)
                            com.tungsten.fclcore.util.Logging.LOG.info("[QQ] 登录成功 user=$nickname")
                            true
                        }
                    }
                    else -> throw ApiException(code, root.optString("message").ifBlank { "QQ 登录失败 ($code)" })
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            com.tungsten.fclcore.util.Logging.LOG.info("[QQ] poll异常: ${e.message}")
            null
        }
    }

    // ==================== 通用请求 ====================

    /** GET 并返回 envelope 根对象（调用方自行取 data） */
    suspend fun get(path: String, requiresAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            execute("GET", path, null, requiresAuth, allowRefresh = true)
        }

    /** POST JSON 并返回 envelope 根对象 */
    suspend fun post(path: String, body: JSONObject = JSONObject(), requiresAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            execute("POST", path, body, requiresAuth, allowRefresh = true)
        }

    /** 宽松列表提取：在 data 下的常见数组 key（posts/tasks/items/notifications...）中找到第一个非空数组；
     *  全都找不到就回退 data 本身是数组的情况。照 StarWave 的 envelope 容错策略。 */
    fun extractList(root: JSONObject): JSONArray {
        if (root.optJSONArray("data") != null) return root.getJSONArray("data")
        val data = root.optJSONObject("data") ?: return JSONArray()
        val keys = listOf(
            "data", "result", "items", "list", "posts", "tasks", "notifications",
            "polls", "seasons", "records", "rows", "rewards", "players", "rankings", "titles", "categories"
        )
        for (key in keys) {
            val arr = data.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        // 兜底：任意一个非空数组成员
        for (key in data.keys()) {
            val arr = data.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        return JSONArray()
    }

    /** 首个匹配键的字符串（RemoteItem.first() 的 Kotlin 版） */
    fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.optString(k, "")
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    /** 头像字段 → 完整URL（avatar 可以是文件名） */
    fun avatarUrl(avatar: String?): String {
        val a = avatar?.trim() ?: ""
        if (a.isEmpty()) return ""
        if (a.startsWith("http")) return a
        return "$BASE/user/avatar/$a"
    }

    private fun execute(
        method: String,
        path: String,
        body: JSONObject?,
        requiresAuth: Boolean,
        allowRefresh: Boolean
    ): JSONObject {
        val builder = Request.Builder().url("$BASE${if (path.startsWith("/")) path else "/$path"}")
        if (requiresAuth && session.accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${session.accessToken}")
        }
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        val resp = client.newCall(builder.build()).execute()
        resp.use { r ->
            val text = r.body?.string() ?: "{}"
            val root = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("code", r.code) }
            // 401 → 刷新重放一次
            if (r.code == 401 && allowRefresh && requiresAuth) {
                val refreshed = kotlinx.coroutines.runBlocking { tryRefresh() }
                if (refreshed) {
                    return execute(method, path, body, requiresAuth, allowRefresh = false)
                }
                throw ApiException(401, "登录已失效，请重新登录")
            }
            val code = root.optInt("code", r.code)
            if (code != 200) {
                throw ApiException(code, root.optString("message").ifBlank { "请求失败 ($code)" })
            }
            return root
        }
    }

    private fun postRaw(path: String, body: JSONObject, requiresAuth: Boolean): JSONObject =
        execute("POST", path, body, requiresAuth, allowRefresh = false)

    class ApiException(val code: Int, message: String) : Exception(message)
}