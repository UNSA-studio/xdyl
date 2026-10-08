package www.xdyl.hygge.com
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
        // 统一携带客户端标识 UA（服务端可能按 UA 区分客户端下发令牌）
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "NebulaUpdater-Android/1.0")
                .build()
            chain.proceed(req)
        }
        .build()

    private val gson = com.google.gson.Gson()

    // ==================== 认证 ====================

    suspend fun login(account: String, password: String, tempToken: String? = null): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("account", account).put("password", password)
            // QQ 登录绑定流程：附带 temp_token，服务端校验账号后把 QQ 绑定到该账号
            if (!tempToken.isNullOrBlank()) body.put("temp_token", tempToken)
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
            LogManager.log("[API] refresh失败: ${e.message}")
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
                LogManager.log("[QQ] poll sid=$sessionId http=${resp.code} body=${text.take(300)}")
                val root = try { JSONObject(text) } catch (e: Exception) { null }
                    ?: return@use null // 非 JSON：继续等

                val code = root.optInt("code", resp.code)
                val data = root.optJSONObject("data")
                val tokens = data?.optJSONObject("tokens") ?: root.optJSONObject("tokens")
                val status = (data?.optString("status", "") ?: "").ifBlank { root.optString("status", "") }

                // === 成功判定（对齐 iOS：status / token 字段优先，不只看 code） ===
                val access = firstNonBlank(
                    data?.optString("access_token"),
                    data?.optString("token"),
                    tokens?.optString("access_token"),
                    root.optString("access_token"),
                    root.optString("token"),
                    data?.optString("jwt"),
                    root.optString("jwt")
                )
                if (status == "success" || !access.isNullOrBlank()) {
                    if (!access.isNullOrBlank()) {
                        val refresh = firstNonBlank(
                            data?.optString("refresh_token"),
                            tokens?.optString("refresh_token"),
                            root.optString("refresh_token")
                        ) ?: ""
                        val nickname = firstNonBlank(
                            data?.optString("nickname"),
                            data?.optString("username"),
                            data?.optJSONObject("user")?.optString("username"),
                            root.optString("nickname"),
                            root.optString("username"),
                            root.optJSONObject("user")?.optString("username")
                        ) ?: "QQ用户"
                        session.saveSession(access, refresh, nickname)
                        LogManager.log("[QQ] 登录成功 user=$nickname（via ${if (status == "success") "status" else "token"}）")
                        return@use true
                    }
                    LogManager.log("[QQ] 判定成功但未取到 token（继续等待）: $text")
                    return@use null
                }

                // === 等待中 ===
                if (code == 202) return@use null

                // === 明确失败（带错误消息的 4xx/5xx，且非"等待"类文案） ===
                val msg = root.optString("message")
                if (code >= 400 && msg.isNotBlank() && !msg.contains("等待")) {
                    throw ApiException(code, msg)
                }

                // 其他过渡态：记录后继续轮询（鲁棒）
                return@use null
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            LogManager.log("[QQ] poll异常: ${e.message}")
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    // ==================== QQ 登录（详细状态） ====================

    /** QQ 轮询详细结果 */
    sealed class QQPoll {
        /** 等待扫码/授权 */
        object Waiting : QQPoll()
        /** 已直接拿到正式令牌 */
        data class Token(val access: String, val refresh: String, val nickname: String) : QQPoll()
        /** 服务端返回 temp_token —— 需要用【账号+密码】调 /login 完成绑定式登录 */
        data class NeedBind(val tempToken: String, val nickname: String) : QQPoll()
    }

    /** 轮询 QQ 授权详细状态（供 QQ 登录绑定流程使用） */
    suspend fun pollQQDetail(sessionId: String): QQPoll = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/check-qq-login?session_id=$sessionId")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                LogManager.log("[QQ] poll sid=$sessionId http=${resp.code} body=${text.take(300)}")
                val root = try { JSONObject(text) } catch (e: Exception) { null }
                    ?: return@use QQPoll.Waiting

                val code = root.optInt("code", resp.code)
                val data = root.optJSONObject("data")
                val tokens = data?.optJSONObject("tokens") ?: root.optJSONObject("tokens")
                val status = (data?.optString("status", "") ?: "").ifBlank { root.optString("status", "") }

                // 1) 正式令牌（若服务端未来支持免密直发）
                val access = firstNonBlank(
                    data?.optString("access_token"), data?.optString("token"),
                    tokens?.optString("access_token"),
                    root.optString("access_token"), root.optString("token")
                )
                if (!access.isNullOrBlank()) {
                    val refresh = firstNonBlank(
                        data?.optString("refresh_token"), tokens?.optString("refresh_token"),
                        root.optString("refresh_token")
                    ) ?: ""
                    val nickname = firstNonBlank(
                        data?.optString("nickname"), data?.optString("username"),
                        root.optString("nickname"), root.optString("username")
                    ) ?: "QQ用户"
                    session.saveSession(access, refresh, nickname)
                    LogManager.log("[QQ] 直接登录成功 user=$nickname")
                    return@use QQPoll.Token(access, refresh, nickname)
                }

                // 2) temp_token —— 绑定式登录（需要账号密码配合）
                val temp = firstNonBlank(
                    data?.optString("temp_token"), root.optString("temp_token")
                )
                if (!temp.isNullOrBlank()) {
                    val nickname = firstNonBlank(
                        data?.optString("qq_nickname"), data?.optString("nickname"),
                        root.optString("qq_nickname"), root.optString("nickname")
                    ) ?: "QQ用户"
                    LogManager.log("[QQ] 需要账号绑定 tempToken=${temp.take(8)}… nickname=$nickname")
                    return@use QQPoll.NeedBind(temp, nickname)
                }

                if (status == "success") {
                    LogManager.log("[QQ] 判定成功但无令牌: $text")
                }
                if (code == 202) return@use QQPoll.Waiting
                val msg = root.optString("message")
                if (code >= 400 && msg.isNotBlank() && !msg.contains("等待")) {
                    throw ApiException(code, msg)
                }
                QQPoll.Waiting
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            LogManager.log("[QQ] poll异常: ${e.message}")
            QQPoll.Waiting
        }
    }

    /** 绑定 QQ 到当前登录账号（POST /user/bind-qq {temp_token}） */
    suspend fun bindQQ(tempToken: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("temp_token", tempToken)
        postRaw("/user/bind-qq", body, requiresAuth = true)
        LogManager.log("[QQ] 绑定成功")
        true
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

    /** 通用请求（自动携带 token，供扩展功能使用）
     *  method: "GET" / "POST"，body 可为 null
     */
    suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        requiresAuth: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        execute(method, path, body ?: JSONObject(), requiresAuth, allowRefresh = false)
    }

    /** 上传图片（multipart/form-data，字段名 file） */
    suspend fun uploadImage(bytes: ByteArray, fileName: String, path: String = "/upload/image"): JSONObject =
        withContext(Dispatchers.IO) {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    okhttp3.RequestBody.create("image/*".toMediaTypeOrNull(), bytes)
                )
                .build()
            val builder = okhttp3.Request.Builder()
                .url(BASE + path)
                .post(body)
                .header("User-Agent", "NebulaUpdater-Android/1.0")
            if (session.accessToken.isNotBlank()) {
                builder.header("Authorization", "Bearer ${session.accessToken}")
            }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(resp.code, text.take(300))
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    private fun postRaw(path: String, body: JSONObject, requiresAuth: Boolean): JSONObject =
        execute("POST", path, body, requiresAuth, allowRefresh = false)

    class ApiException(val code: Int, message: String) : Exception(message)
}