package www.xdyl.hygge.com

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 私钥备份加密（B1 + B3 混合方案）。
 *
 * 设计目标：**服务端永远拿不到私钥明文**。
 *
 * B1（登录密码派生）：用户重新登录、输入密码后，可自动恢复私钥。
 * B3（一次性恢复码）：额外生成 20 位恢复码，用户抄下来；
 *     忘了密码时用它恢复。恢复码只在生成那一刻展示一次，服务端只存密文。
 *
 * 加密结构：
 *   KEK = PBKDF2-HMAC-SHA256(secret, salt, 210000)   → 32 字节
 *   blob = base64(salt | nonce | AES-GCM(KEK, 私钥PKCS8))
 *
 * 两种 secret 各存一份 blob：
 *   - backup_pwd  ：secret = 登录密码
 *   - backup_code ：secret = 恢复码（规整化后）
 *
 * 为什么用 PBKDF2 而不是直接 hash：密码熵低，必须加计算成本。
 * 210000 次是现代 OWASP 建议区间的下限，手机上约 200-400ms，可接受。
 */
object ChatKeyBackup {

    private const val AES = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PBKDF2_ITER = 210_000
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_LEN = 32

    /** 恢复码字符集：去掉容易混淆的 0/O/1/I/L */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    /** 恢复码位数 */
    private const val CODE_LEN = 20

    // ---------------------------------------------------------------- 恢复码

    /** 生成一个新的恢复码，形如 XXXX-XXXX-XXXX-XXXX-XXXX */
    fun newRecoveryCode(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(CODE_LEN + 4)
        repeat(CODE_LEN) { i ->
            if (i > 0 && i % 4 == 0) sb.append('-')
            sb.append(CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    /**
     * 规整化恢复码用于派生密钥。
     *
     * 用户输入时可能带空格、小写、漏横线，统一成大写去分隔符再参与派生，
     * 否则抄错一个字符就永远恢复不了。
     */
    fun normalizeCode(code: String): String =
        code.uppercase().filter { it in CODE_ALPHABET }

    /**
     * 轻量校验：是否像是一个结构正确的恢复码。
     * 只做格式检查，不会用来验证正确性（正确性只能靠解密是否成功判断）。
     */
    fun looksLikeCode(code: String): Boolean = normalizeCode(code).length == CODE_LEN

    // ---------------------------------------------------------------- 加解密

    /**
     * 用 secret 加密私钥，返回可直接上传的 Base64 blob。
     */
    fun encrypt(privateKeyPkcs8: ByteArray, secret: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rnd.nextBytes(it) }

        val kek = deriveKek(secret, salt)
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ct = cipher.doFinal(privateKeyPkcs8)

        val out = ByteArray(SALT_LEN + NONCE_LEN + ct.size)
        System.arraycopy(salt, 0, out, 0, SALT_LEN)
        System.arraycopy(nonce, 0, out, SALT_LEN, NONCE_LEN)
        System.arraycopy(ct, 0, out, SALT_LEN + NONCE_LEN, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * 用 secret 解密。
     *
     * 返回 null 表示 secret 错误或数据损坏 —— 这是唯一的验证手段，
     * 因为 AES-GCM 自带认证标签，密码错必然抛 AEADBadTagException。
     */
    fun decrypt(blobB64: String, secret: String): ByteArray? {
        return try {
            val raw = Base64.decode(blobB64, Base64.NO_WRAP)
            if (raw.size <= SALT_LEN + NONCE_LEN + TAG_BITS / 8) return null

            val salt = raw.copyOfRange(0, SALT_LEN)
            val nonce = raw.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN)
            val ct = raw.copyOfRange(SALT_LEN + NONCE_LEN, raw.size)

            val kek = deriveKek(secret, salt)
            val cipher = Cipher.getInstance(AES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }

    /** PBKDF2-HMAC-SHA256 派生 32 字节 KEK */
    private fun deriveKek(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITER, KEY_LEN * 8)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(spec).encoded
    }

    /**
     * 展示给用户时的格式化（每 4 位加横线）。
     */
    fun formatCode(code: String): String {
        val n = normalizeCode(code)
        val sb = StringBuilder()
        n.forEachIndexed { i, c ->
            if (i > 0 && i % 4 == 0) sb.append('-')
            sb.append(c)
        }
        return sb.toString()
    }
}