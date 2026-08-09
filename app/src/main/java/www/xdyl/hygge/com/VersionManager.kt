package www.xdyl.hygge.com

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModEntry(
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String? = null,
    @SerializedName("old_version") val oldVersion: String? = null,
    @SerializedName("new_version") val newVersion: String? = null
)

data class VersionDiff(
    @SerializedName("version") val version: String,
    @SerializedName("added") val added: List<ModEntry>,
    @SerializedName("removed") val removed: List<ModEntry>,
    @SerializedName("updated") val updated: List<ModEntry>
)

class VersionManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("version", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val BASE_URL = "https://unsa-fdws.cc.cd/api/download/"
    private val API_KEY = "US.Kx9Qm2p"

    fun getLocalVersion(): String = prefs.getString("local_version", "0.0") ?: "0.0"

    fun saveLocalVersion(version: String) {
        prefs.edit().putString("local_version", version).apply()
    }

    suspend fun checkAndUpdate(
        onUpdateAvailable: (VersionDiff) -> Unit,
        onComplete: () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                LogManager.log("[CSV] Fetching ${BASE_URL}Version_difference.json")
                val request = Request.Builder()
                    .url("${BASE_URL}Version_difference.json")
                    .header("X-API-Key", API_KEY)
                    .build()
                val response = client.newCall(request).execute()
                LogManager.log("[CSV] Response code: ${response.code}")
                if (!response.isSuccessful) {
                    LogManager.log("[CSV] API returned error: ${response.code} ${response.message}")
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                val json = response.body?.string()
                if (json == null) {
                    LogManager.log("[CSV] Response body is null")
                    withContext(Dispatchers.Main) { onComplete() }
                    return@withContext
                }
                LogManager.log("[CSV] Received JSON: ${json.take(200)}...")
                val remote = gson.fromJson(json, VersionDiff::class.java)
                val localVer = getLocalVersion()
                LogManager.log("[CSV] Remote version: ${remote.version}, local: $localVer")
                if (compareVersion(remote.version, localVer) > 0) {
                    withContext(Dispatchers.Main) { onUpdateAvailable(remote) }
                } else {
                    withContext(Dispatchers.Main) { onComplete() }
                }
            } catch (e: Exception) {
                LogManager.log("[CSV] Exception: ${e.javaClass.simpleName} - ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    suspend fun downloadNewCsv(version: String) {
        withContext(Dispatchers.IO) {
            var lastEx: Exception? = null
            for (attempt in 1..5) {
                try {
                    LogManager.log("[CSV] 下载尝试 $attempt/5 ...")
                    val request = Request.Builder()
                        .url("${BASE_URL}file_list.csv")
                        .header("X-API-Key", API_KEY)
                        .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body ?: continue
                        val csv = body.string()
                        LogManager.log("[CSV] 下载成功, 大小=${csv.length} 字符, 行数=${csv.lines().size}")
                        val lineCount = csv.lines().size
                        if (lineCount < 80) {
                            LogManager.log("[CSV] 警告: 下载的 CSV 仅有 $lineCount 行, 可能不完整!")
                            lastEx = Exception("CSV 不完整: 仅 $lineCount 行")
                            if (attempt < 5) kotlinx.coroutines.delay((1000L * attempt).coerceAtMost(5000))
                            continue
                        }
                        val file = File(context.filesDir, "file_list.csv")
                        file.writeText(csv)
                        saveLocalVersion(version)
                        LogManager.log("[CSV] 已保存到 ${file.absolutePath} (版本 $version)")
                        return@withContext
                    } else {
                        lastEx = Exception("HTTP ${response.code}")
                        LogManager.log("[CSV] 下载失败 (${attempt}/5): HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    lastEx = e
                    LogManager.log("[CSV] 下载异常 (${attempt}/5): ${e.javaClass.simpleName} - ${e.message}")
                }
                if (attempt < 5) kotlinx.coroutines.delay((1000L * attempt).coerceAtMost(5000))
            }
            LogManager.log("[CSV] 下载最终失败: ${lastEx?.message}")
        }
    }

    private fun compareVersion(v1: String, v2: String): Int {
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
