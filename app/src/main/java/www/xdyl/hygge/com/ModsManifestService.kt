package www.xdyl.hygge.com

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 新接口清单服务（mods.json 协议）——安全接入第一步。
 *
 * 本类只负责"获取 + 解析"，不改变任何现有（CSV）更新行为；
 * 供后续渐进接入使用。
 *
 * 依据 API_COMPATIBILITY.md：
 *  - 模组清单端点：http://api.lanternwaves.fun:5551/mods/mods.json
 *  - 下载目标使用 data.files（name / url / sha256 / size / kind），不使用 /mods/list
 */
class ModsManifestService(private val context: Context) {

    companion object {
        /** 模组清单端点（与旧 CSV 链路并存，互不影响） */
        const val MANIFEST_URL = "http://api.lanternwaves.fun:5551/mods/mods.json"
    }

    data class Manifest(
        @SerializedName("manifest_version") val manifestVersion: Int = 0,
        @SerializedName("pack_version") val packVersion: String = "",
        @SerializedName("generated_at") val generatedAt: String? = null,
        @SerializedName("files") val files: List<ManifestFile> = emptyList(),
        @SerializedName("removed") val removed: List<RemovedEntry> = emptyList()
    )

    data class ManifestFile(
        @SerializedName("name") val name: String,
        @SerializedName("path") val path: String? = null,
        @SerializedName("size") val size: Long = 0,
        @SerializedName("kind") val kind: String? = null,
        @SerializedName("group") val group: String? = null,
        @SerializedName("time") val time: String? = null,
        @SerializedName("url") val url: String,
        @SerializedName("sha256") val sha256: String? = null
    )

    data class RemovedEntry(
        @SerializedName("name") val name: String,
        @SerializedName("path") val path: String? = null
    )

    private val client = OkHttpClient.Builder().dns(NetDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** 拉取并解析清单；失败抛出异常（调用方自行决定降级策略） */
    suspend fun fetch(): Manifest = withContext(Dispatchers.IO) {
        LogManager.log("[MANIFEST] 拉取 $MANIFEST_URL")
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("清单获取失败：HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw RuntimeException("清单响应为空")
            val root = gson.fromJson(body, JsonObject::class.java)
            val code = try {
                root.get("code")?.asInt ?: -1
            } catch (e: Exception) {
                -1
            }
            if (code != 200) {
                throw RuntimeException("清单接口返回 code=$code")
            }
            val data = root.getAsJsonObject("data") ?: throw RuntimeException("清单缺少 data 字段")
            gson.fromJson(data, Manifest::class.java)
        }
    }

    /** 安全获取：失败返回 null 并写日志（供 UI 渐进接入，不打断现有流程） */
    suspend fun fetchOrNull(): Manifest? = try {
        fetch()
    } catch (e: Exception) {
        LogManager.log("[MANIFEST] 获取失败（安全降级）: ${e.message}")
        null
    }
}