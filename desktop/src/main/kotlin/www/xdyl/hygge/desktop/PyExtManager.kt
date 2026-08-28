package www.xdyl.hygge.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Windows 端扩展包管理器（与 Android 端 terminal 自动安装流程对齐）。
 * 扩展包 = 纯净嵌入式 Python + pip（python_windows.zip / tar.gz，不含任何组件）。
 * 安装位置: ~/.xdyl/python_root/
 */
object PyExtManager {

    const val DEFAULT_URL = "https://pan.vma.cc/pan/d/b5a092911a4933d8cc8f151c5873a3d5?ext=gz"

    fun pythonRoot(): File = File(System.getProperty("user.home"), ".xdyl/python_root")

    fun pythonExe(): File? {
        val exe = File(pythonRoot(), "python.exe")
        return if (exe.exists()) exe else null
    }

    fun isReady(): Boolean = pythonExe()?.canExecute() ?: false

    /** 下载并解压扩展包。返回 null 表示成功，否则为错误信息 */
    suspend fun install(onProgress: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            onProgress("开始下载扩展程序包...")
            val tmp = File.createTempFile("pyext", ".zip")
            val dlClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder().url(DEFAULT_URL).build()
            val call = dlClient.newCall(request)
            val resp = call.execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    tmp.delete()
                    return@withContext "HTTP ${r.code}"
                }
                val body = r.body
                if (body == null) {
                    tmp.delete()
                    return@withContext "响应体为空"
                }
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(65536)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            read = input.read(buf)
                            if (read == -1) break
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
            // Windows 10+ 自带 bsdtar，自动识别 zip / tar.gz 等格式，无需区分扩展包类型
            val proc = ProcessBuilder("tar", "-xf", tmp.absolutePath, "-C", root.absolutePath)
                .redirectErrorStream(false)
            val p = proc.start()
            val errOut = p.errorStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                tmp.delete()
                return@withContext "解压超时"
            }
            tmp.delete()
            if (p.exitValue() != 0) {
                return@withContext "解压失败: ${errOut.trim().take(200).ifBlank { "未知错误" }}"
            }
            flattenSingleDir(root)
            if (!isReady()) {
                return@withContext "解压后未找到 python.exe"
            }
            LogManager.log("[EXT] 扩展程序包已安装到: ${root.absolutePath}")
            null
        } catch (e: Exception) {
            LogManager.log("[EXT] 安装异常: ${e.javaClass.simpleName} - ${e.message}")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 安装 mcstatus（纯净扩展包不含任何组件，首次使用时现场补装） */
    fun installMcstatus(): String? {
        return try {
            val exe = pythonExe()
            if (exe == null) return "Python 未安装"
            LogManager.log("[EXT] 正在安装 mcstatus...")
            val pb = ProcessBuilder(exe.absolutePath, "-m", "pip", "install", "mcstatus")
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val p = pb.start()
            p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val err = p.errorStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return "pip 安装超时"
            }
            if (p.exitValue() == 0) {
                LogManager.log("[EXT] mcstatus 安装完成")
                null
            } else {
                err.trimEnd().lineSequence().lastOrNull()?.take(200) ?: "未知错误"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** 解压后若只有一个顶层目录，则把内容整体上提到根（兼容带/不带顶层目录的包） */
    private fun flattenSingleDir(root: File) {
        val entries = root.listFiles() ?: return
        val single = entries.singleOrNull { it.isDirectory } ?: return
        if (single.name.equals("python_root", true)) return
        val tmpName = File(root, "__flatten_tmp")
        if (!single.renameTo(tmpName)) return
        tmpName.listFiles()?.forEach { f ->
            if (!f.renameTo(File(root, f.name))) {
                f.copyRecursively(File(root, f.name), overwrite = true)
            }
        }
        tmpName.deleteRecursively()
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
    fun queryPing(): String {
        return try {
            val exe = pythonExe()
            if (exe == null) return "错误: Python 未安装"
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
}