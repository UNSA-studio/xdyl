package www.xdyl.hygge.com

import android.content.Context
import java.security.MessageDigest

/**
 * 私钥备份的编排层。
 *
 * 把 ChatCrypto（加密）、ChatKeyBackup（派生密钥）、ChatApi（传输）
 * 串起来，对上层只暴露三个动作：备份、凭密码恢复、凭恢复码恢复。
 */
object ChatKeyBackupService {

    /** KDF 描述，随密文一起存服务端，方便将来换参数时兼容旧数据 */
    private const val KDF_DESC = "pbkdf2-sha256:210000:aes256-gcm"

    /** 本地保存恢复码（仅供设置页回看，不作为唯一凭据） */
    private const val PREF = "nu_chat_backup"
    private const val KEY_CODE = "recovery_code"

    // ---------------------------------------------------------------- 备份

    /**
     * 生成恢复码并上传两份备份。
     *
     * @param password 登录密码（明文，只在内存中用，绝不落盘）
     * @return 生成的恢复码；失败为 null
     */
    fun backup(context: Context, password: String): String? {
        return try {
            ChatCrypto.init(context)
            val priv = ChatCrypto.exportPrivateKeyPkcs8() ?: return null
            val pub = ChatCrypto.exportPublicKeySpki() ?: return null

            // 把私钥和公钥打包（公钥也存，恢复时不用从私钥反推 —— Android 做不到）
            // 用 ByteBuffer 而不是 byteArrayOf：长度可能大于 127，byte 是有符号的
            val header = java.nio.ByteBuffer.allocate(8)
                .putInt(priv.size)
                .putInt(pub.size)
                .array()
            val payload = header + priv + pub

            val code = ChatKeyBackup.newRecoveryCode()
            val normCode = ChatKeyBackup.normalizeCode(code)

            // 两份密文：一份用登录密码，一份用恢复码
            val pwdBlob = ChatKeyBackup.encrypt(payload, password)
            val codeBlob = ChatKeyBackup.encrypt(payload, normCode)
            val codeHash = sha256Hex(normCode)

            if (!ChatApi.uploadKeyBackup(context, pwdBlob, codeBlob, codeHash, KDF_DESC)) {
                LogManager.w("[CHAT] 备份上传失败")
                return null
            }

            // 本地留一份，设置页可查看
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_CODE, code).apply()

            LogManager.log("[CHAT] 私钥备份完成")
            code
        } catch (e: Exception) {
            LogManager.e("[CHAT] 备份异常: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 用密码恢复。
     *
     * 流程：取服务端备份 → 用密码解密 pwdBlob → 装回本机密钥 → 重新登记公钥。
     * 成功后对端不需要做任何事，因为公钥没变。
     */
    fun restoreWithPassword(context: Context, password: String): Boolean {
        return try {
            val backup = ChatApi.fetchKeyBackup(context) ?: run {
                LogManager.w("[CHAT] 服务端没有备份")
                return false
            }
            val payload = ChatKeyBackup.decrypt(backup.pwdBlob, password)
            if (payload == null) {
                LogManager.w("[CHAT] 密码错误，解密失败")
                return false
            }
            installPayload(context, payload)
        } catch (e: Exception) {
            LogManager.e("[CHAT] 密码恢复异常: ${e.message}")
            false
        }
    }

    /**
     * 用恢复码恢复。
     */
    fun restoreWithCode(context: Context, code: String): Boolean {
        return try {
            val norm = ChatKeyBackup.normalizeCode(code)
            if (!ChatKeyBackup.looksLikeCode(norm)) {
                LogManager.w("[CHAT] 恢复码格式不对")
                return false
            }
            val backup = ChatApi.lookupKeyBackup(context, sha256Hex(norm)) ?: run {
                LogManager.w("[CHAT] 恢复码不匹配任何备份")
                return false
            }
            val payload = ChatKeyBackup.decrypt(backup.codeBlob, norm)
            if (payload == null) {
                LogManager.w("[CHAT] 恢复码错误，解密失败")
                return false
            }
            installPayload(context, payload)
        } catch (e: Exception) {
            LogManager.e("[CHAT] 恢复码恢复异常: ${e.message}")
            false
        }
    }

    /** 解包 payload 并装回本机 */
    private fun installPayload(context: Context, payload: ByteArray): Boolean {
        if (payload.size < 8) return false

        fun intAt(o: Int): Int =
            ((payload[o].toInt() and 0xFF) shl 24) or
            ((payload[o + 1].toInt() and 0xFF) shl 16) or
            ((payload[o + 2].toInt() and 0xFF) shl 8) or
            (payload[o + 3].toInt() and 0xFF)

        val privLen = intAt(0)
        val pubLen = intAt(4)
        if (privLen <= 0 || pubLen <= 0 || 8 + privLen + pubLen != payload.size) return false

        val priv = payload.copyOfRange(8, 8 + privLen)
        val pub = payload.copyOfRange(8 + privLen, 8 + privLen + pubLen)

        if (!ChatCrypto.installKeyPair(context, priv, pub)) return false

        // 公钥没变，但保险起见重新登记一次（服务端记录可能被清过）
        runCatching {
            val user = ChatIdentity.current(context)?.nickname.orEmpty()
            if (user.isNotBlank()) {
                ChatApi.uploadKey(context, user, ChatCrypto.publicKeyBase64())
            }
        }

        LogManager.log("[CHAT] 私钥恢复成功")
        return true
    }

    /** 本地保存的恢复码（可能为 null） */
    fun savedCode(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_CODE, null)

    private fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}