package www.xdyl.hygge.desktop

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModEntry @JvmOverloads constructor(
    val name: String,
    val version: String = "",
    @SerializedName("old_version") val oldVersion: String = "",
    @SerializedName("new_version") val newVersion: String = ""
)

data class VersionDiff @JvmOverloads constructor(
    val version: String,
    val added: List<ModEntry> = emptyList(),
    val removed: List<ModEntry> = emptyList(),
    val updated: List<ModEntry> = emptyList()
)

class VersionManager(private val prefs: Preferences) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val BASE_URL = "https://unsa-fdws.cc.cd/api/download/"
    private val API_KEY = "US.Kx9Qm2p"

    fun getLocalVersion(): String = prefs.getString("local_version", "0.0") ?: "0.0"

    fun saveLocalVersion(version: String) {
        prefs.putString("local_version", version)
    }

    suspend fun checkAndUpdate(
        onUpdateAvailable: (VersionDiff) -> Unit,
        onComplete: () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                LogManager.log("[CSV] 检查云端版本...")
                val request = Request.Builder()
                    .url("${BASE_URL}Version_difference.json")
                    .header("X-API-Key", API_KEY)
                    .build()
                val response = client.newCall(request).execute()
                LogManager.log("[CSV] 服务器响应: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    LogManager.log("[CSV] 服务器返回错误: ${response.code}")
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                val json = response.body?.string() ?: run {
                    LogManager.log("[CSV] 响应体为空")
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                LogManager.log("[CSV] 收到JSON: ${json.take(200)}...")
                val remote = gson.fromJson(json, VersionDiff::class.java)
                val localVer = getLocalVersion()
                LogManager.log("[CSV] 云端版本=${remote.version}, 本地版本=$localVer")
                if (compareVersions(remote.version, localVer) > 0) {
                    LogManager.log("[CSV] 发现新版本! added=${remote.added.size}, removed=${remote.removed.size}, updated=${remote.updated.size}")
                    withContext(Dispatchers.Main) { onUpdateAvailable(remote) }
                } else {
                    LogManager.log("[CSV] 已是最新版本")
                    withContext(Dispatchers.Main) { onComplete() }
                }
            } catch (e: Exception) {
                LogManager.log("[CSV] 版本检查异常: ${e.javaClass.simpleName} - ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    suspend fun downloadNewCsv(version: String) {
        withContext(Dispatchers.IO) {
            try {
                LogManager.log("[CSV] 开始下载 file_list.csv...")
                val request = Request.Builder()
                    .url("${BASE_URL}file_list.csv")
                    .header("X-API-Key", API_KEY)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val csv = response.body?.string() ?: return@withContext
                    LogManager.log("[CSV] 下载成功, 大小: ${csv.length} 字节")
                    val dir = File(System.getProperty("user.home"), ".xdyl")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "file_list.csv")
                    file.writeText(csv)
                    saveLocalVersion(version)
                    LogManager.log("[CSV] 已保存到: ${file.absolutePath} (版本 $version)")
                } else {
                    LogManager.log("[CSV] 下载失败: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                LogManager.log("[CSV] 下载异常: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}
