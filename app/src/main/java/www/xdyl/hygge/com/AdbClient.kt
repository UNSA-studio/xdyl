package www.xdyl.hygge.com

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 精简 ADB 客户端 — TCP 连接 ADB daemon 并执行 shell 命令
 * 协议参考: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
object AdbClient {

    /** 发送 ADB 消息: 4 字节 hex 长度 + payload */
    private fun OutputStream.sendAdb(msg: String) {
        val payload = msg.toByteArray(Charsets.UTF_8)
        val header = String.format("%04X", payload.size).toByteArray()
        write(header)
        write(payload)
        flush()
    }

    /** 读取 ADB 消息，返回 payload 字符串 */
    private fun InputStream.readAdb(): String {
        val header = ByteArray(4)
        var read =0
        while (read <4) {
            val n = this.read(header, read, 4 - read)
            if (n <0) throw Exception("连接断开")
            read += n
        }
        val len = String(header, Charsets.UTF_8).toInt(16)
        val payload = ByteArray(len)
        read =0
        while (read < len) {
            val n = this.read(payload, read, len - read)
            if (n <0) throw Exception("连接断开")
            read += n
        }
        return String(payload, Charsets.UTF_8)
    }

    /** 生成 ADB RSA 密钥对（3072 位），首次使用需用户在设备上授权 */
    private fun generateAdbKey(): Pair<RSAPublicKey, RSAPrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(3072)
        val kp = gen.generateKeyPair()
        return Pair(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
    }

    /** 将 RSA 公钥编码为 ADB AUTH 消息所需格式 */
    private fun RSAPublicKey.toAdbAuthPayload(): ByteArray {
        // ADB 期望: 32-bit little-endian 长度 + "ADB RSA public key\0" + Base64 编码的公钥 + '\0'
        val keyBytes = Base64.getEncoder().encode(encoded)
        val header = "ADB RSA public key\u0000".toByteArray()
        val out = ByteArray(4 + header.size + keyBytes.size +1)
        // 小端 32-bit 长度 = header.size + keyBytes.size +1
        val total = header.size + keyBytes.size +1
        out[0] = (total and0xFF).toByte()
        out[1] = ((total shr8) and0xFF).toByte()
        out[2] = ((total shr16) and0xFF).toByte()
        out[3] = ((total shr24) and0xFF).toByte()
        System.arraycopy(header, 0, out, 4, header.size)
        System.arraycopy(keyBytes, 0, out, 4 + header.size, keyBytes.size)
        out[out.size -1] =0
        return out
    }

    /** 用 RSA 私钥签名 token */
    private fun RSAPrivateKey.signToken(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(this)
        sig.update(token)
        return sig.sign()
    }

    /** 执行 shell 命令，返回标准输出 */
    fun exec(host: String, port: Int, command: String): String {
        val startTime = System.currentTimeMillis()
        val timeout = 10_000L // 10 秒超时

        Socket(host, port).use { sock ->
            sock.soTimeout = timeout
            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())

            // 1. 读取 banner
            val banner = input.readAdb()

            // 2. 发送 AUTH 签名认证
            val (pubKey, privKey) = generateAdbKey()
            val authPayload = pubKey.toAdbAuthPayload()
            output.write("AUTH".toByteArray())
            output.writeInt(Integer.reverseBytes(authPayload.size)) // 大端序 32-bit
            output.write(authPayload)
            output.flush()

            // 3. 接收 AUTH 响应（可能是 token 挑战）
            val authResponse = input.readAdb()
            if (authResponse.startsWith("FAIL")) {
                // 设备未授权此密钥 — 需要用户手动确认
                throw Exception("ADB 认证失败 — 请在设备上授权调试连接")
            }

            // 如果收到 SIGNATURE token，计算签名并发送
            if (authResponse.startsWith("SIGNATURE")) {
                val tokenHex = authResponse.substringAfter("SIGNATURE").trim()
                val token = tokenHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val signature = privKey.signToken(token)
                output.write("AUTH".toByteArray())
                output.writeInt(Integer.reverseBytes(signature.size))
                output.write(signature)
                output.flush()

                val finalResponse = input.readAdb()
                if (finalResponse.startsWith("FAIL")) {
                    throw Exception("ADB 签名验证失败")
                }
                // CONNECT 消息表示认证成功
            }

            // 4. 选择设备（host:transport-any）
            output.sendAdb("host:transport-any")

            // 5. 发送 shell 命令
            output.sendAdb("shell:$command")

            // 6. 读取所有输出
            val sb = StringBuilder()
            try {
                while (true) {
                    val line = input.readAdb()
                    sb.append(line)
                    if (line == "CLSE" || line.startsWith("CLSE")) break
                }
            } catch (_: Exception) {
                // 超时或正常结束
            }

            return sb.toString().trim()
        }
    }
}