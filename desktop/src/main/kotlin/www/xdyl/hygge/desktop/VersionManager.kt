package www.xdyl.hygge.desktop

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class VersionDiff(
    val version: String,
    val added: List<String>,
    val removed: List<String>,
    val updated: List<String>
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
                val request = Request.Builder()
                    .url("${BASE_URL}Version_difference.json")
                    .header("X-API-Key", API_KEY)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    onComplete()
                    return@withContext
                }
                val json = response.body?.string() ?: return@withContext
                val remote = gson.fromJson(json, VersionDiff::class.java)
                val localVer = getLocalVersion()
                if (remote.version > localVer) {
                    onUpdateAvailable(remote)
                } else {
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete()
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
                    val file = File(System.getProperty("user.home"), ".xdyl/file_list.csv")
                    file.parentFile?.mkdirs()
                    file.writeText(csv)
                    saveLocalVersion(version)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
