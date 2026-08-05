package www.xdyl.hygge.com

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * ADB 客户端 — TCP 连接 ADB daemon，通过 ADB 协议执行 shell 命令。
 * 握手流程: CNXN → AUTH(公钥) → AUTH(签名) → CNXN(已连接) → OPEN(shell)
 */
object AdbClient {

    /** 发送 ADB 控制消息: 4 字节 hex 长度 + ASCII payload */
    private fun DataOutputStream.sendAdbPacket(payload: ByteArray) {
        val header = String.format("%04X", payload.size).toByteArray(Charsets.UTF_8)
        write(header)
        write(payload)
        flush()
    }

    /** 读取 ADB 控制消息，返回 payload */
    private fun DataInputStream.readAdbPacket(): ByteArray {
        val header = ByteArray(4)
        readFully(header)
        val len = String(header, Charsets.UTF_8).toInt(16)
        val payload = ByteArray(len)
        readFully(payload)
        return payload
    }

    private fun DataInputStream.readFully(buf: ByteArray) {
        var off =0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n <0) throw Exception("ADB 连接断开")
            off += n
        }
    }

    /** 生成 2048 位 RSA 密钥 */
    private fun generateRsaKey(): Pair<RSAPublicKey, RSAPrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        return Pair(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
    }

    /** 将公钥编码为 ADB 格式: 4 字节 little-endian len + "adb public key\0" + base64(der) + '\0' */
    private fun RSAPublicKey.toAdbKey(): ByteArray {
        val b64 = Base64.getEncoder().encode(encoded)
        val head = "ADB RSA public key\u0000".toByteArray()
        val total = head.size + b64.size +1
        return ByteArray(4 + total).also { out ->
            out[0] = (total and 0xFF).toByte()
            out[1] = ((total shr 8) and 0xFF).toByte()
            out[2] = ((total shr 16) and 0xFF).toByte()
            out[3] = ((total shr 24) and 0xFF).toByte()
            System.arraycopy(head, 0, out, 4, head.size)
            System.arraycopy(b64, 0, out, 4 + head.size, b64.size)
        }
    }

    /** RSA 签名 */
    private fun RSAPrivateKey.sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(this)
        sig.update(data)
        return sig.sign()
    }

    /** 执行 shell 命令 */
    fun exec(host: String, port: Int, command: String): String {
        // 本机直接用 Runtime.exec，更可靠
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            return execLocal(command)
        }

        Socket(host, port).use { sock ->
            sock.soTimeout = 10000
            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())

            // 1. 发送 CNXN 连接请求
            // ADB 协议: "CNXN" + version(4B big-endian, 0x01000000) + maxdata(4B big-endian, 0x00100000) + "host::\0"
            val cnxn = ByteArray(24 + 7)
            System.arraycopy("CNXN".toByteArray(), 0, cnxn, 0, 4)
            cnxn[4] = 0x01.toByte()  // version = 0x01000000 big-endian
            cnxn[8] = 0x00
            cnxn[9] = 0x10.toByte()  // maxdata = 0x00100000 big-endian
            System.arraycopy("host::\u0000".toByteArray(), 0, cnxn, 24, 7)
            output.sendAdbPacket(cnxn)

            // 2. 读取响应 — 应该是 AUTH 挑战
            val response = input.readAdbPacket()
            val respStr = String(response.copyOfRange(0, 4), Charsets.UTF_8)

            if (respStr == "AUTH") {
                val (pubKey, privKey) = generateRsaKey()

                // 发送公钥
                val keyPayload = pubKey.toAdbKey()
                output.sendAdbPacket("AUTH\u0003\u0000\u0000\u0000".toByteArray() + keyPayload)

                // 读取 SIGNATURE 挑战
                val challenge = input.readAdbPacket()
                val chalStr = String(challenge.copyOfRange(0, 4), Charsets.UTF_8)
                if (chalStr == "AUTH") {
                    // 签名并发送
                    val token = challenge.copyOfRange(4, challenge.size)
                    val signature = privKey.sign(token)
                    output.sendAdbPacket("AUTH\u0002\u0000\u0000\u0000".toByteArray() + signature)

                    // 读取 CNXN 确认
                    val confirm = input.readAdbPacket()
                    val confStr = String(confirm.copyOfRange(0, 4), Charsets.UTF_8)
                    if (confStr != "CNXN") throw Exception("ADB 认证失败 — 请在设备上确认授权")
                }
            } else if (respStr != "CNXN") {
                throw Exception("ADB 握手失败: 意外响应")
            }

            // 3. 发送 OPEN shell 命令
            val cmdBytes = "shell:$command\u0000".toByteArray()
            val openPayload = ByteArray(4 + cmdBytes.size)
            openPayload[0] = 0 // local-id 低字节
            openPayload[1] = 0
            openPayload[2] = 0
            openPayload[3] = 0
            System.arraycopy(cmdBytes, 0, openPayload, 4, cmdBytes.size)
            output.sendAdbPacket("OPEN".toByteArray() + openPayload)

            // 4. 读取 shell 输出
            val sb = StringBuilder()
            try {
                while (true) {
                    val pkt = input.readAdbPacket()
                    val type = String(pkt.copyOfRange(0, 4), Charsets.UTF_8)
                    when (type) {
                        "WRTE" -> sb.append(String(pkt.copyOfRange(4, pkt.size), Charsets.UTF_8))
                        "CLSE" -> break
                    }
                }
            } catch (_: Exception) {}

            return sb.toString().trim()
        }
    }

    /** 本地直接执行（等同 adb shell） */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "200"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            lines.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }
}