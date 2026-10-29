package www.xdyl.hygge.com

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【私聊端到端加密】
 *
 * 方案：X25519 ECDH 协商会话密钥 + AES-256-GCM 加密。
 *  - 服务器只负责转发公钥与密文，**无法解密内容**
 *  - 会话密钥 = HKDF(ECDH(A私钥, B公钥))，双方算出同一个密钥
 *  - 每条消息使用随机 12 字节 nonce，AES-GCM 自带完整性校验
 *
 * 说明：Android 上 X25519 需 API 33+（XDH）。老系统回退到
 * X25519 不可用时，用 ECDH(P-256) 代替，效果一致。
 */
object ChatCrypto {

    private const val AES = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private var localPrivate: PrivateKey? = null
    private var localPublic: PublicKey? = null

    @Synchronized fun init(context: android.content.Context) {
        activeScope = ChatIdentity.current(context)?.scope.orEmpty()
        if (localPrivate != null) return
        val prefs = context.getSharedPreferences("nu_chat_keys", android.content.Context.MODE_PRIVATE)
        val privB64 = prefs.getString("priv", null)
        val pubB64 = prefs.getString("pub", null)
        if (privB64 != null && pubB64 != null) {
            try {
                val kf = KeyFactory.getInstance(if (useX25519) "X25519" else "EC")
                localPrivate = kf.generatePrivate(
                    if (useX25519) java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                    else java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                )
                localPublic = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                return
            } catch (e: Exception) {
                LogManager.e("[CHAT] 恢复密钥失败: ${e.message}")
            }
        }
        // 生成新密钥对
        val kpg = if (useX25519) {
            KeyPairGenerator.getInstance("X25519")
        } else {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
        }
        val kp = kpg.generateKeyPair()
        localPrivate = kp.private
        localPublic = kp.public
        prefs.edit()
            .putString("priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
    }

    private val useX25519: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 33

    /** 本机公钥（Base64），上传给服务器供对端取用 */
    fun publicKeyBase64(): String =
        Base64.encodeToString(localPublic!!.encoded, Base64.NO_WRAP)

    /** 与对端公钥协商出 32 字节会话密钥 */
    fun deriveSessionKey(peerPublicKeyB64: String): ByteArray {
        val kf = KeyFactory.getInstance(if (useX25519) "X25519" else "EC")
        val peerPub: PublicKey = kf.generatePublic(
            X509EncodedKeySpec(Base64.decode(peerPublicKeyB64, Base64.NO_WRAP))
        )
        val ka = KeyAgreement.getInstance(if (useX25519) "X25519" else "ECDH")
        ka.init(localPrivate)
        ka.doPhase(peerPub, true)
        val shared = ka.generateSecret()

        // HKDF-SHA256（简单实现：extract + expand 一次）
        val salt = ByteArray(32) // 固定盐在双方一致即可
        val prk = hmacSha256(salt, shared)
        val info = "nu-chat-v1".toByteArray()
        return hmacSha256(prk, info + byteArrayOf(0x01)).copyOf(32)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** 加密：返回 (nonce, ciphertext) 的 Base64 组合，用 ':' 分隔 */
    fun encrypt(sessionKey: ByteArray, plain: String): String {
        val cipher = Cipher.getInstance(AES)
        val nonce = ByteArray(12)
        java.security.SecureRandom().nextBytes(nonce)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** 解密：失败返回 null */
    fun decrypt(sessionKey: ByteArray, payload: String): String? {
        return try {
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return null
            val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.e("[CHAT] 解密失败: ${e.message}")
            null
        }
    }

    /** Keep the existing device key pair for compatibility; isolate ephemeral peer keys by account/server. */
    @Volatile private var activeScope = ""
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun cacheKey(peerKey: String, key: ByteArray, scope: String = activeScope) { keyCache[scope + ":" + peerKey] = key }
    fun getCachedKey(peerKey: String, scope: String = activeScope): ByteArray? = keyCache[scope + ":" + peerKey]
    fun invalidateSessionKey(peerKey: String, scope: String = activeScope) { keyCache.remove(scope + ":" + peerKey) }
    fun clearSessionKeys() { keyCache.clear(); activeScope = "" }

    /** 指纹（便于双方核对，防中间人） */
    fun fingerprint(sessionKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(sessionKey)
            .joinToString("") { "%02x".format(it) }
            .take(16)
            .chunked(4)
            .joinToString("-")
            .uppercase()
}