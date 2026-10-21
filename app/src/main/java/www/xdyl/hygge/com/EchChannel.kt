package www.xdyl.hygge.com

import android.content.Context

/**
 * ECH 通道：用 BoringSSL 起一条加密了 SNI 的 TLS 连接，发一个 HTTP/1.1 请求。
 *
 * 只负责「一条请求」的收发与最小解析（状态码 + body），不处理连接复用、重定向。
 * 主流程里作为 HTTPS 的首选方式，失败就回退到明文/系统 TLS。
 */
object EchChannel {

    private const val CRLF = "\r\n"

    @Volatile private var cfgHost: String = ""
    @Volatile private var cfgBytes: ByteArray? = null
    @Volatile private var cfgAt: Long = 0L

    /** 拿 ECHConfig（缓存 10 分钟）；拿不到返回 null */
    private fun configFor(host: String): ByteArray? {
        val now = System.currentTimeMillis()
        val cached = cfgBytes
        if (cached != null && cfgHost == host && now - cfgAt < 10 * 60 * 1000L) return cached
        val info = EchProbe.fetch(host) ?: return null
        val bytes = try {
            android.util.Base64.decode(info.echB64, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null
        cfgHost = host
        cfgBytes = bytes
        cfgAt = now
        LogManager.i("[ECH] ECHConfig 就绪（${bytes.size} 字节，来自 ${info.dns}）")
        return bytes
    }

    data class Resp(val code: Int, val body: String, val chunked: Boolean)

    /**
     * @param baseUrl  形如 https://nu-pe-chat.cc.cd（端口只支持 443）
     * @param pathWithQuery 例如 /Android/chat/ping 或 /Android/chat/pull?cursor=0
     */
    fun request(
        context: Context,
        baseUrl: String,
        pathWithQuery: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): Resp? {
        if (!EchTls.available()) return null

        val host = baseUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        if (host.isBlank()) return null

        val cfg = configFor(host) ?: return null

        val sb = StringBuilder()
        sb.append(method).append(' ').append(pathWithQuery).append(" HTTP/1.1").append(CRLF)
        sb.append("Host: ").append(host).append(CRLF)
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append(CRLF) }
        if (body != null) {
            sb.append("Content-Length: ").append(body.size).append(CRLF)
            if (!headers.keys.any { it.equals("Content-Type", true) }) {
                sb.append("Content-Type: application/json").append(CRLF)
            }
        }
        sb.append("Connection: close").append(CRLF)
        sb.append(CRLF)

        val head = sb.toString().toByteArray(Charsets.UTF_8)
        val raw = head + (body ?: ByteArray(0))

        val respBytes = EchTls.request(host, 443, cfg, raw) ?: run {
            LogManager.w("[ECH] 请求失败（${method} $pathWithQuery）")
            return null
        }
        return parse(respBytes)
    }

    private fun parse(bytes: ByteArray): Resp? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.ISO_8859_1)
        val headEnd = text.indexOf("\r\n\r\n")
        if (headEnd < 0) {
            LogManager.w("[ECH] 响应不完整：${text.take(80)}")
            return null
        }
        val head = text.substring(0, headEnd)
        val lines = head.split(CRLF)
        val statusLine = lines.firstOrNull() ?: return null
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        val chunked = head.contains("Transfer-Encoding: chunked", true)

        // body 是字节层，不能按字符串切（ISO_8859_1 是 1:1 映射，可安全用下标）
        val bodyStart = headEnd + 4
        val bodyBytes = bytes.copyOfRange(bodyStart, bytes.size)
        val body = if (chunked) dechunk(bodyBytes) else bodyBytes
        return Resp(code, String(body, Charsets.UTF_8), chunked)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var lineEnd = i
            while (lineEnd + 1 < data.size && !(data[lineEnd] == 13.toByte() && data[lineEnd + 1] == 10.toByte())) lineEnd++
            if (lineEnd + 1 >= data.size) break
            val sizeStr = String(data, i, lineEnd - i, Charsets.ISO_8859_1).trim().substringBefore(';')
            val size = sizeStr.toIntOrNull(16) ?: break
            i = lineEnd + 2
            if (size == 0) break
            val end = minOf(i + size, data.size)
            out.write(data, i, end - i)
            i = end + 2
        }
        return out.toByteArray()
    }
}
