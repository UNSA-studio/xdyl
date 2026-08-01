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

data class VersionDiff(
    @SerializedName("version") val version: String,
    @SerializedName("added") val added: List<String>,
    @SerializedName("removed") val removed: List<String>,
    @SerializedName("updated") val updated: List<String>
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
                    val csv = response.body?.string() ?: return@withContext
                    val file = File(context.filesDir, "file_list.csv")
                    file.writeText(csv)
                    saveLocalVersion(version)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
