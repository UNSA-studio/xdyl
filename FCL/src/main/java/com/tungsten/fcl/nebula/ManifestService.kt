package com.tungsten.fcl.nebula

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * mods.json 清单服务（http://api.lanternwaves.fun:5551/mods/mods.json）。
 *
 * 清单结构（manifest_version 2）：
 * {
 *   "code":200,
 *   "data": {
 *     "manifest_version":2, "pack_version":"d0.9.4",
 *     "files":[{name,path,size,kind,group,time,url,sha256}],
 *     "removed":[{name,group,removed_since}]
 *   }
 * }
 * 分组：modpack（整合包zip）、new_mod（追加模组jar）、tacz（枪包zip）
 */
class ManifestService {

    data class ManifestFile(
        val name: String,
        val path: String,
        val size: Long,
        val kind: String,
        val group: String,
        val time: String,
        val url: String,
        val sha256: String
    )

    data class Manifest(
        val manifestVersion: Int,
        val packVersion: String,
        val files: List<ManifestFile>,
        val removed: List<ManifestFile>
    ) {
        val modpacks get() = files.filter { it.group == "modpack" }
        val newMods get() = files.filter { it.group == "new_mod" }
        val taczPacks get() = files.filter { it.group == "tacz" }
        /**
         * 手机端要装的主整合包：文件名以 NAST 开头的 MCBBS 格式包。
         * serverfix*.zip 是给电脑用户解压的 PCL2 便携版（PCL2.exe + mrpack），跳过。
         */
        val latestModpack: ManifestFile? get() =
            modpacks.filter { it.name.startsWith("NAST", ignoreCase = true) }
                .maxByOrNull { it.time }
                ?: modpacks.filterNot { it.name.startsWith("serverfix", ignoreCase = true) }
                    .maxByOrNull { it.time }
                    ?: modpacks.maxByOrNull { it.time }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): Manifest = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(ManifestUrls.MANIFEST).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("mods.json HTTP ${resp.code}")
            val root = JSONObject(resp.body!!.string())
            val code = root.optInt("code", resp.code)
            if (code != 200) throw Exception("mods.json code $code")
            parse(root.getJSONObject("data"))
        }
    }

    private fun parse(data: JSONObject): Manifest {
        val filesJson = data.optJSONArray("files") ?: org.json.JSONArray()
        val files = mutableListOf<ManifestFile>()
        for (i in 0 until filesJson.length()) {
            val f = filesJson.getJSONObject(i)
            files.add(
                ManifestFile(
                    name = f.optString("name"),
                    path = f.optString("path", f.optString("name")),
                    size = f.optLong("size", 0),
                    kind = f.optString("kind", ""),
                    group = f.optString("group", ""),
                    time = f.optString("time", ""),
                    url = f.optString("url"),
                    sha256 = f.optString("sha256", "")
                )
            )
        }
        val removedJson = data.optJSONArray("removed") ?: org.json.JSONArray()
        val removed = mutableListOf<ManifestFile>()
        for (i in 0 until removedJson.length()) {
            val f = removedJson.getJSONObject(i)
            removed.add(
                ManifestFile(
                    name = f.optString("name"),
                    path = f.optString("path", f.optString("name")),
                    size = f.optLong("size", 0),
                    kind = f.optString("kind", ""),
                    group = f.optString("group", ""),
                    time = f.optString("removed_since", ""),
                    url = "",
                    sha256 = ""
                )
            )
        }
        return Manifest(
            manifestVersion = data.optInt("manifest_version", 0),
            packVersion = data.optString("pack_version", ""),
            files = files,
            removed = removed
        )
    }
}

object ManifestUrls {
    const val MANIFEST = "http://api.lanternwaves.fun:5551/mods/mods.json"
}