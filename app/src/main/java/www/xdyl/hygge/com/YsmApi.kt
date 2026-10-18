package www.xdyl.hygge.com

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 【YSM 皮肤服务客户端】
 *
 * 服务：http://api.lanternwaves.fun:8080（Flask，独立于主站）
 *
 * 接线依据：iOS 客户端 docs/API_COMPATIBILITY.md（2026-10-13 EXE 静态契约）
 *   GET  /history?player={player}     → { history: [{file_md5, is_duplicate, model_id,
 *                                        original_model_id, player, upload_time}], player }
 *   POST /upload_v2 (multipart)        → 字段 code / player_name / is_public / skin_file
 *   POST /confirm_duplicate            → { code, player_name, original_model_id }
 *
 * 注意：上传使用 1MB 分块写临时文件再上传，避免内存爆掉（iOS 侧同样做法）。
 */
object YsmApi {

    const val PREF_BASE = "ysm_base"
    const val DEFAULT_BASE = "http://api.lanternwaves.fun:8080"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .dns(NetDns)
        .build()

    fun base(context: Context): String {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_BASE, DEFAULT_BASE)?.trim()?.trimEnd('/') ?: DEFAULT_BASE
    }

    fun setBase(context: Context, url: String) {
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .edit().putString(PREF_BASE, url.trim().trimEnd('/')).apply()
    }

    /** 一条皮肤记录 */
    data class Skin(
        val fileMd5: String,
        val modelId: String,
        val originalModelId: String?,
        val isDuplicate: Boolean,
        val player: String,
        val uploadTime: String
    )

    /** 查询某玩家的皮肤历史；失败返回 null */
    fun history(context: Context, player: String): List<Skin>? {
        val url = base(context) + "/history?player=" + android.net.Uri.encode(player)
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogManager.w("[YSM] history $player -> ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val arr = root.optJSONArray("history") ?: JSONArray()
                val out = mutableListOf<Skin>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        Skin(
                            fileMd5 = o.optString("file_md5", o.optString("md5", "")),
                            modelId = o.optString("model_id", ""),
                            originalModelId = o.optString("original_model_id", "").ifBlank { null },
                            isDuplicate = o.optBoolean("is_duplicate", false),
                            player = o.optString("player", player),
                            uploadTime = o.optString("upload_time", "")
                        )
                    )
                }
                LogManager.d("[YSM] history $player -> ${out.size} 条")
                out
            }
        } catch (e: Exception) {
            LogManager.e("[YSM] history $player 失败: ${e.message}")
            null
        }
    }

    /** 上传结果 */
    sealed class UploadResult {
        data class Ok(val raw: JSONObject) : UploadResult()
        data class Duplicate(val raw: JSONObject) : UploadResult()
        data class Fail(val message: String) : UploadResult()
    }

    /**
     * 上传皮肤（multipart）
     *
     * 字段：code（兑换码）/ player_name / is_public / skin_file
     * 文件会先复制到 cache 再上传，避免直接引用外部路径失败。
     */
    fun upload(
        context: Context,
        code: String,
        playerName: String,
        isPublic: Boolean,
        file: File
    ): UploadResult {
        if (!file.isFile) return UploadResult.Fail("文件不存在")
        val maxBytes = 32L * 1024 * 1024
        if (file.length() > maxBytes) return UploadResult.Fail("文件过大（上限 32MB）")

        return try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("code", code)
                .addFormDataPart("player_name", playerName)
                .addFormDataPart("is_public", if (isPublic) "true" else "false")
            bodyBuilder.addFormDataPart(
                "skin_file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            val req = Request.Builder()
                .url(base(context) + "/upload_v2")
                .post(bodyBuilder.build())
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                LogManager.i("[YSM] upload -> ${resp.code} ${text.take(200)}")
                if (!resp.isSuccessful) return UploadResult.Fail("HTTP ${resp.code}: ${text.take(120)}")
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return UploadResult.Fail("响应不是 JSON：${text.take(120)}")
                val status = json.optString("status", "")
                if (status.equals("duplicate", true) || json.optBoolean("duplicate", false)) {
                    UploadResult.Duplicate(json)
                } else {
                    UploadResult.Ok(json)
                }
            }
        } catch (e: Exception) {
            LogManager.e("[YSM] upload 失败: ${e.message}")
            UploadResult.Fail(e.message ?: "上传失败")
        }
    }

    /** 重复皮肤二次授权 */
    fun confirmDuplicate(
        context: Context,
        code: String,
        playerName: String,
        originalModelId: String
    ): Boolean {
        return try {
            val body = JSONObject()
                .put("code", code)
                .put("player_name", playerName)
                .put("original_model_id", originalModelId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(base(context) + "/confirm_duplicate")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                LogManager.i("[YSM] confirm_duplicate -> ${resp.code} ${text.take(160)}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            LogManager.e("[YSM] confirm_duplicate 失败: ${e.message}")
            false
        }
    }
}