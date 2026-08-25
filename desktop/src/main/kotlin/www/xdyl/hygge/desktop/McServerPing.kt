package www.xdyl.hygge.desktop

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minecraft Java 版服务器状态查询（原生协议实现）。
 * 握手(0x00, nextState=1) -> Status Request(0x00) -> 读 JSON 响应。
 */
object McServerPing {

    private fun varInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return out.toByteArray()
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readByte().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw RuntimeException("VarInt 过长")
        }
    }

    /** 返回 Triple(JSON文本, 延迟ms, null) 或 Triple(null, 0, 错误信息) */
    fun ping(host: String, port: Int = 25565): Triple<String?, Long, String?> {
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                val out = DataOutputStream(socket.getOutputStream())

                // Handshake 包: id=0x00, protocol=754(1.16+), host, port, nextState=1
                val hsPayload = ByteArrayOutputStream()
                hsPayload.write(varInt(0x00))
                hsPayload.write(varInt(754))
                hsPayload.write(varInt(host.length))
                hsPayload.write(host.toByteArray(Charsets.UTF_8))
                hsPayload.write(((port shr 8) and 0xFF))
                hsPayload.write(port and 0xFF)
                hsPayload.write(varInt(1))

                val hsData = hsPayload.toByteArray()
                out.write(varInt(hsData.size))
                out.write(hsData)
                out.flush()

                // Status Request: 无负载, 仅 id=0x00
                val reqData = varInt(0x00)
                out.write(varInt(reqData.size))
                out.write(reqData)
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                readVarInt(input)               // 包总长
                readVarInt(input)               // 包 id (0x00)
                val jsonLen = readVarInt(input)
                val buf = ByteArray(jsonLen)
                input.readFully(buf)
                val latency = System.currentTimeMillis() - start
                return Triple(String(buf, Charsets.UTF_8), latency, null)
            }
        } catch (e: Exception) {
            return Triple(null, 0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 从 JSON 中提取 description 文本并剥离颜色码 */
    fun extractDescription(json: String): String {
        return try {
            // 简易提取: "description":{...text...} 或 "description":"..."
            val descMatch = Regex("\"description\"\\s*:\\s*\\{[^}]*?\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(json)?.groupValues?.get(1)
                ?: Regex("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
                ?: ""
            descMatch.replace("\\u00a7[0-9a-fk-or]".toRegex(RegexOption.IGNORE_CASE), "")
                .replace("§[0-9a-fk-or]".toRegex(RegexOption.IGNORE_CASE), "")
                .replace("\\n", "\n")
                .take(120)
        } catch (_: Exception) { "" }
    }

    /** 从 JSON 提取在线人数 */
    fun extractPlayers(json: String): Pair<Int, Int>? {
        return try {
            val m = Regex("\"players\"\\s*:\\s*\\{[^}]*\"online\"\\s*:\\s*(\\d+)[^}]*\"max\"\\s*:\\s*(\\d+)").find(json)
                ?: Regex("\"players\"\\s*:\\s*\\{[^}]*\"max\"\\s*:\\s*(\\d+)[^}]*\"online\"\\s*:\\s*(\\d+)").find(json)
            m?.let {
                if (it.groupValues.size > 2 && it.groupValues[2].toIntOrNull() != null && it.groupValues[1].contains(Regex("^\\d+$"))) {
                    val g = it.groupValues
                    if (g[1].toInt() <= g[2].toInt()) Pair(g[1].toInt(), g[2].toInt())
                    else Pair(g[2].toInt(), g[1].toInt())
                } else null
            }
        } catch (_: Exception) { null }
    }
}