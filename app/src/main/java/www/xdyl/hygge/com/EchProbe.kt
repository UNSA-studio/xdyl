package www.xdyl.hygge.com

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * ECH 探测：从 DNS 的 HTTPS(type 65) 记录里取出 `ech=` 参数。
 *
 * 背景：服务端（Cloudflare）已启用 ECH，DoH 查询返回：
 *   1 . alpn=h3,h2 ipv4hint=... ech=AEX+DQBBUgAgACCaYBWA...AAA=
 * 其中 ech 是 ECHConfigList，客户端必须拿到它才能构造加密 ClientHello。
 *
 * 这一步只验证「这台手机的网络能不能拿到 ech」：
 *   - 明文 UDP 53 查询（8.8.8.8 / 1.1.1.1 / 223.5.5.5）
 *   - 若返回的 HTTPS 记录里带 ech（SvcParam key=5），说明后续做 BoringSSL 有意义
 *   - 若全都被污染/丢弃，就说明这条网络连 ECHConfig 都拿不到，路走不通
 *
 * 纯手写 DNS 报文解析，不引第三方库（APK 体积不变）。
 */
object EchProbe {

    data class Info(
        val host: String,
        val dns: String,
        val echLen: Int,
        val echB64: String,
        val alpn: String,
        val outerName: String
    )

    private const val TYPE_HTTPS = 65

    /** 依次尝试多个 DNS，返回第一条成功解析出 ech 的结果 */
    fun fetch(host: String, timeoutMs: Int = 2500): Info? {
        val servers = listOf("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1")
        for (dns in servers) {
            try {
                val raw = queryUdp(host, dns, timeoutMs) ?: continue
                val parsed = parseHttpsRecord(raw) ?: continue
                if (parsed.ech != null) {
                    LogManager.i(
                        "[ECH] 从 $dns 拿到 ECHConfig：${parsed.ech.size} 字节，" +
                            "outer=${parsed.target}，alpn=${parsed.alpn}"
                    )
                    return Info(
                        host = host,
                        dns = dns,
                        echLen = parsed.ech.size,
                        echB64 = b64(parsed.ech),
                        alpn = parsed.alpn,
                        outerName = parsed.target
                    )
                } else {
                    LogManager.w("[ECH] $dns 返回了 HTTPS 记录，但没有 ech 参数")
                }
            } catch (e: Exception) {
                LogManager.w("[ECH] 查询 $dns 失败：${e.message}")
            }
        }
        LogManager.e("[ECH] 所有 DNS 都没拿到 ech（网络可能污染/拦截了 type 65 查询）")
        return null
    }

    // ---------------- DNS wire format ----------------

    private fun queryUdp(host: String, dns: String, timeoutMs: Int): ByteArray? {
        val packet = buildQuery(host, TYPE_HTTPS)
        val addr = InetAddress.getByName(dns)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(packet, packet.size, addr, 53))
            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return buf.copyOf(resp.length)
        }
    }

    private fun buildQuery(host: String, qtype: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // header：id, flags(0x0100 表示 RD), qd=1
        out.write(0x12); out.write(0x34)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        // QNAME
        for (label in host.trim('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b, 0, b.size)
        }
        out.write(0)
        // QTYPE / QCLASS
        out.write((qtype shr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    private class Svcb(val target: String, val alpn: String, val ech: ByteArray?)

    private fun parseHttpsRecord(resp: ByteArray): Svcb? {
        if (resp.size < 12) return null
        val ancount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        if (ancount <= 0) return null

        var off = 12
        // 跳过 question
        off = skipName(resp, off) ?: return null
        off += 4
        if (off > resp.size) return null

        repeat(ancount) {
            val nameEnd = skipName(resp, off) ?: return null
            off = nameEnd
            if (off + 10 > resp.size) return null
            val type = ((resp[off].toInt() and 0xFF) shl 8) or (resp[off + 1].toInt() and 0xFF)
            val rdlen = ((resp[off + 8].toInt() and 0xFF) shl 8) or (resp[off + 9].toInt() and 0xFF)
            val rdStart = off + 10
            if (rdStart + rdlen > resp.size) return null

            if (type == TYPE_HTTPS) {
                var p = rdStart
                p += 2 // priority
                // target name（可能是压缩指针，需要在整报文里解析）
                val tName = readName(resp, p)
                p = tName.second
                var target = tName.first
                if (target.isBlank()) target = "."

                var alpn = ""
                var ech: ByteArray? = null

                // SvcParams
                while (p + 4 <= rdStart + rdlen) {
                    val key = ((resp[p].toInt() and 0xFF) shl 8) or (resp[p + 1].toInt() and 0xFF)
                    val len = ((resp[p + 2].toInt() and 0xFF) shl 8) or (resp[p + 3].toInt() and 0xFF)
                    val vStart = p + 4
                    if (vStart + len > resp.size) break
                    when (key) {
                        1 -> { // alpn：每项是 "长度+字符串"
                            val sb = StringBuilder()
                            var q = vStart
                            while (q < vStart + len) {
                                val l = resp[q].toInt() and 0xFF
                                if (q + 1 + l > vStart + len) break
                                if (sb.isNotEmpty()) sb.append(',')
                                sb.append(String(resp, q + 1, l, Charsets.US_ASCII))
                                q += 1 + l
                            }
                            alpn = sb.toString()
                        }
                        5 -> ech = resp.copyOfRange(vStart, vStart + len) // ECHConfigList
                    }
                    p = vStart + len
                }
                return Svcb(target, alpn, ech)
            }
            off = rdStart + rdlen
        }
        return null
    }

    /** 跳过（可能压缩的）名字，返回下一个位置 */
    private fun skipName(buf: ByteArray, start: Int): Int? {
        var i = start
        while (i < buf.size) {
            val len = buf[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if ((len and 0xC0) == 0xC0) return i + 2 // 压缩指针正好 2 字节
            i += 1 + len
        }
        return null
    }

    /** 读取名字，返回 (文本, 下一个位置) */
    private fun readName(buf: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start
        var next = start
        var jumped = false
        var guard = 0
        while (i < buf.size && guard++ < 64) {
            val len = buf[i].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) next = i + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (i + 1 >= buf.size) break
                val ptr = ((len and 0x3F) shl 8) or (buf[i + 1].toInt() and 0xFF)
                if (!jumped) next = i + 2
                jumped = true
                i = ptr
                continue
            }
            if (i + 1 + len > buf.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(buf, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return sb.toString() to next
    }

    private fun b64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
}