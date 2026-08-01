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
                if (remote.version > localVer) {
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
            try {
                val request = Request.Builder()
                    .url("${BASE_URL}file_list.csv")
                    .header("X-API-Key", API_KEY)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body ?: run {
                        LogManager.log("[CSV] Download failed: empty body")
                        return@withContext
                    }
                    val csv = body.string()
                    LogManager.log("[CSV] Downloaded file_list.csv, size=${csv.length} chars, lines=${csv.lines().size}")
                    // 完整性检查：CSV 至少要有 80 行（表头 + 至少 80 个模组）
                    val lineCount = csv.lines().size
                    if (lineCount < 80) {
                        LogManager.log("[CSV] WARNING: downloaded CSV has only $lineCount lines, might be truncated!")
                        // 不保存不完整的文件
                        return@withContext
                    }
                    val file = File(context.filesDir, "file_list.csv")
                    file.writeText(csv)
                    saveLocalVersion(version)
                    LogManager.log("[CSV] Saved to ${file.absolutePath}")
                } else {
                    LogManager.log("[CSV] Download failed: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                LogManager.log("[CSV] Download exception: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }
}
