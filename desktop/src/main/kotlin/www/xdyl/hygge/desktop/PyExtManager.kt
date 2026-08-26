package www.xdyl.hygge.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Windows 端扩展包管理器（与 Android 端 terminal 自动安装流程对齐）。
 * 扩展包 = 官方嵌入式 Python + pip + mcstatus（python_windows.zip）。
 * 安装位置: ~/.xdyl/python_root/
 */
object PyExtManager {

    const val DEFAULT_URL = "https://unsa-fdws.cc.cd/api/download/python_windows.zip"

    fun pythonRoot(): File = File(System.getProperty("user.home"), ".xdyl/python_root")

    fun pythonExe(): File? {
        val exe = File(pythonRoot(), "python.exe")
        return if (exe.exists()) exe else null
    }

    fun isReady(): Boolean = pythonExe()?.canExecute() ?: false

    /** 下载并解压扩展包。返回 null 表示成功，否则为错误信息 */
    suspend fun install(onProgress: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            val url = DEFAULT_URL
            onProgress("开始下载扩展程序包...")
            val tmp = File.createTempFile("pyext", ".zip")
            val dlClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder().url(url).build()
            dlClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val body = resp.body ?: return@withContext "响应体为空"
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(65536)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            fos.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct > lastPct && pct % 2 == 0) {
                                    lastPct = pct
                                    onProgress("下载中 $pct% (${done / 1048576}MB)")
                                }
                            }
                        }
                    }
                }
            }
            onProgress("解压中...")
            val root = pythonRoot()
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(root, stripTopLevel(entry.name))
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        out.setExecutable(true, false)
                    }
                    entry = zis.nextEntry
                }
            }
            tmp.delete()
            if (!isReady()) return@withContext "解压后未找到 python.exe"
            LogManager.log("[EXT] 扩展程序包已安装到: ${root.absolutePath}")
            null
        } catch (e: Exception) {
            LogManager.log("[EXT] 安装异常: ${e.javaClass.simpleName} - ${e.message}")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 兼容 zip 内带顶层目录或不带两种打包方式 */
    private fun stripTopLevel(name: String): String {
        val n = name.replace('\\', '/')
        val idx = n.indexOf('/')
        return if (idx > 0) n.substring(idx + 1) else n
    }

    /** 与 Android 端输出格式完全一致的查询脚本（Windows 有系统 DNS，无需手动 Resolver） */
    private fun pingScript(server: String = "mc.lanternwaves.fun:25565"): String = """
import sys
try:
    from mcstatus import JavaServer
    s = JavaServer.lookup('$server')
    st = s.status()
    print('状态: 在线')
    try:
        print('延迟: %d ms' % round(s.ping()))
    except Exception:
        pass
    desc = str(st.description)
    import re
    desc = re.sub(r'[§\u00a7][0-9a-fk-or]', '', desc)
    print('描述: %s' % desc[:120])
except ImportError:
    print('错误: mcstatus 未安装')
except Exception as e:
    print('查询失败: %s' % e)
""".trimIndent()

    /** 用扩展包 Python 执行 mcstatus 查询 */
    fun queryPing(): String = try {
        val exe = pythonExe() ?: return "错误: Python 未安装"
        val script = File(System.getProperty("java.io.tmpdir"), "xdyl_mc_ping.py")
        script.writeText(pingScript())
        val pb = ProcessBuilder(exe.absolutePath, script.absolutePath)
        // 强制子进程 stdout 走 UTF-8，避免中文输出被按 GBK 编码写出导致乱码
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        pb.redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val err = p.errorStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return "查询失败: 超时"
        }
        script.delete()
        if (out.isNotBlank()) out.trimEnd() else "查询失败: ${err.trimEnd().ifBlank { "未知错误" }}"
    } catch (e: Exception) {
        "查询异常: ${e.message}"
    }
}