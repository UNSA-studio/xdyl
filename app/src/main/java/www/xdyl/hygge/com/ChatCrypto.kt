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
 * 三条实现路径，优先级从高到低：
 *   ① 系统 X25519（API 33+，java.security XDH）
 *   ② 原生 X25519（BoringSSL，见 EchTls.x25519Generate/Shared）
 *   ③ P-256（系统 ECDH）
 *
 * 为什么必须有 ②：iOS 端用 X25519。如果 Android 走 ③ P-256，
 * 两边曲线不同，数学上算不出同一个共享密钥 —— 消息必然解不开。
 * 而 Android 13 以下没有系统 X25519，所以只能用 BoringSSL 补。
 * ③ 只作为原生库不可用时的最后兜底（此时与 iOS 仍然不通）。
 */
object ChatCrypto {

    private const val AES = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private var localPrivate: PrivateKey? = null
    private var localPublic: PublicKey? = null

    /** 原生 X25519 的裸 32 字节密钥（与上面两个互斥，二选一） */
    @Volatile private var rawPriv: ByteArray? = null
    @Volatile private var rawPub: ByteArray? = null

    @Synchronized fun init(context: android.content.Context) {
        activeScope = ChatIdentity.current(context)?.scope.orEmpty()
        if (localPrivate != null || rawPriv != null) return
        val prefs = context.getSharedPreferences("nu_chat_keys", android.content.Context.MODE_PRIVATE)
        val privB64 = prefs.getString("priv", null)
        val pubB64 = prefs.getString("pub", null)

        // 已有密钥：先看它是什么算法，再决定用哪条通道加载。
        if (privB64 != null && pubB64 != null) {
            val stored = try {
                Base64.decode(pubB64, Base64.NO_WRAP)
            } catch (e: Exception) { null }
            val storedIsX25519 = stored != null && isX25519Spki(stored)

            if (storedIsX25519 && nativeX25519) {
                // 本机存的是 X25519，且原生库可用 → 走原生通道
                val raw = extractX25519Spki(stored!!)
                val privRaw = extractX25519Pkcs8(Base64.decode(privB64, Base64.NO_WRAP))
                if (raw != null && privRaw != null) {
                    rawPriv = privRaw; rawPub = raw
                    LogManager.log("[CHAT] 载入原生 X25519 密钥")
                    return
                }
            } else if (!storedIsX25519) {
                // 本机存的是 P-256
                if (!preferX25519) {
                    // 系统不支持也拿不到原生库 → 只能继续用 P-256
                    try {
                        val kf = KeyFactory.getInstance("EC")
                        localPrivate = kf.generatePrivate(
                            java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                        )
                        localPublic = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                        LogManager.log("[CHAT] 载入 P-256 密钥（无 X25519 可用）")
                        return
                    } catch (e: Exception) {
                        LogManager.e("[CHAT] 恢复 P-256 密钥失败: ${e.message}")
                    }
                } else {
                    // 原生 X25519 可用，但本地还是 P-256 —— 这正是跨端解不开的根因。
                    // 必须换成 X25519，旧密钥由备份机制兜底。
                    //
                    // 关键：先把旧公钥记成"服务器当前值"，这样稍后登记新公钥时
                    // 能带上正确的 expectedPublicKey，通过服务端的防替换校验。
                    // 这把旧公钥是我们自己生成并登记过的，用它自证是正当的。
                    ChatApi.rememberRegisteredKey(context, pubB64)
                    LogManager.w("[CHAT] 本地为 P-256，与 iOS(X25519) 不兼容，切换为 X25519")
                }
            } else {
                // X25519 密钥但原生库不可用 → 系统 X25519（API 33+）
                try {
                    val kf = KeyFactory.getInstance("X25519")
                    localPrivate = kf.generatePrivate(
                        java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                    )
                    localPublic = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                    return
                } catch (e: Exception) {
                    LogManager.e("[CHAT] 恢复系统 X25519 失败: ${e.message}")
                }
            }
        }

        // 生成新密钥对
        when {
            nativeX25519 -> {
                val priv = ByteArray(32)
                val pub = ByteArray(32)
                if (EchTls.x25519Generate(priv, pub)) {
                    rawPriv = priv; rawPub = pub
                    prefs.edit()
                        .putString("priv", Base64.encodeToString(wrapX25519Pkcs8(priv), Base64.NO_WRAP))
                        .putString("pub", Base64.encodeToString(wrapX25519Spki(pub), Base64.NO_WRAP))
                        .apply()
                    LogManager.log("[CHAT] 生成原生 X25519 密钥对")
                    return
                }
                LogManager.e("[CHAT] 原生 X25519 生成失败，回退")
            }
            systemX25519 -> {
                runCatching {
                    val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
                    localPrivate = kp.private
                    localPublic = kp.public
                    prefs.edit()
                        .putString("priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
                        .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
                        .apply()
                    LogManager.log("[CHAT] 生成系统 X25519 密钥对")
                    return
                }.onFailure { LogManager.e("[CHAT] 系统 X25519 生成失败: ${it.message}") }
            }
        }

        // 最后兜底：P-256
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        localPrivate = kp.private
        localPublic = kp.public
        prefs.edit()
            .putString("priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        LogManager.w("[CHAT] 退回到 P-256（无法与 iOS 互通）")
    }

    // ---------------- X25519 通道选择 ----------------

    /** 原生 BoringSSL 的 X25519 是否可用 */
    private val nativeX25519: Boolean
        get() = EchTls.available()

    /** 系统自带 X25519（API 33+） */
    private val systemX25519: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 33

    /** 是否优先使用 X25519（用于与 iOS 互通） */
    private val preferX25519: Boolean
        get() = nativeX25519 || systemX25519

    /** 是否走原生原始字节通道 */
    private val useRawNative: Boolean
        get() = rawPriv != null

    // ---------------- X25519 的 SPKI / PKCS#8 包装 ----------------
    //
    // 服务端只认标准编码，所以即便内部用裸 32 字节，存取也必须是标准格式。
    // X25519 的包装是固定前缀，长度恒定，不需要 ASN.1 库。

    private val X25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    )
    private val X25519_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e,
        0x04, 0x22, 0x04, 0x20
    )

    private fun isX25519Spki(spki: ByteArray): Boolean {
        if (spki.size != X25519_SPKI_PREFIX.size + 32) return false
        for (i in X25519_SPKI_PREFIX.indices) if (spki[i] != X25519_SPKI_PREFIX[i]) return false
        return true
    }

    private fun extractX25519Spki(spki: ByteArray): ByteArray? {
        if (!isX25519Spki(spki)) return null
        return spki.copyOfRange(X25519_SPKI_PREFIX.size, spki.size)
    }

    private fun extractX25519Pkcs8(pkcs8: ByteArray): ByteArray? {
        if (pkcs8.size != X25519_PKCS8_PREFIX.size + 32) return null
        for (i in X25519_PKCS8_PREFIX.indices) if (pkcs8[i] != X25519_PKCS8_PREFIX[i]) return null
        return pkcs8.copyOfRange(X25519_PKCS8_PREFIX.size, pkcs8.size)
    }

    private fun wrapX25519Spki(raw: ByteArray): ByteArray = X25519_SPKI_PREFIX + raw

    private fun wrapX25519Pkcs8(raw: ByteArray): ByteArray = X25519_PKCS8_PREFIX + raw

    /**
     * 导出私钥（PKCS#8 原始字节），供备份加密使用。
     *
     * 只给 ChatKeyBackup 用：私钥离开这个对象前必须先被密码派生密钥加密，
     * 任何地方都不允许把返回值直接落盘或上传。
     */
    @Synchronized fun exportPrivateKeyPkcs8(): ByteArray? =
        rawPriv?.let { wrapX25519Pkcs8(it) } ?: localPrivate?.encoded

    /** 导出公钥（X.509 SPKI 原始字节），与私钥一起进备份 */
    @Synchronized fun exportPublicKeySpki(): ByteArray? =
        rawPub?.let { wrapX25519Spki(it) } ?: localPublic?.encoded

    /**
     * 用备份恢复出来的私钥安装到本机，并同步更新本地缓存。
     *
     * 关键点：
     *   1. 公钥必须与私钥匹配 —— 备份里同时存私钥和公钥，
     *      所以这里两个都接受，不再尝试"从私钥反推公钥"（Android 没有这个 API，
     *      X25519 尤其做不到，硬做会得到错误结果）。
     *   2. 私钥换了，所有已协商的会话密钥必须作废，否则还用旧密钥解密。
     */
    @Synchronized fun installKeyPair(context: android.content.Context,
                                      privateKeyPkcs8: ByteArray,
                                      publicKeySpki: ByteArray): Boolean {
        return try {
            // X25519 走原生裸字节通道；其它（P-256）走 JCA
            if (isX25519Spki(publicKeySpki) && nativeX25519) {
                val rawPubBytes = extractX25519Spki(publicKeySpki) ?: return false
                val rawPrivBytes = extractX25519Pkcs8(privateKeyPkcs8) ?: return false
                rawPriv = rawPrivBytes
                rawPub = rawPubBytes
                localPrivate = null
                localPublic = null
            } else {
                val kf = KeyFactory.getInstance(if (isX25519Spki(publicKeySpki)) "X25519" else "EC")
                localPrivate = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyPkcs8))
                localPublic = kf.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeySpki))
                rawPriv = null
                rawPub = null
            }

            val prefs = context.getSharedPreferences("nu_chat_keys", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("priv", Base64.encodeToString(privateKeyPkcs8, Base64.NO_WRAP))
                .putString("pub", Base64.encodeToString(publicKeySpki, Base64.NO_WRAP))
                .apply()

            keyCache.clear()
            true
        } catch (e: Exception) {
            LogManager.e("[CHAT] 安装密钥对失败: ${e.message}")
            false
        }
    }

    /**
     * 本机当前使用的曲线（上报给服务端，用于挑对端的同算法公钥）。
     *
     * 取值与服务端 v2_keys.algorithm 一致：X25519 / P256。
     * 空串表示尚未初始化，调用方应退化为"不带 algorithm 参数"的老行为。
     */
    fun currentAlgorithm(): String {
        val priv = rawPriv
        if (priv != null) return "X25519"
        val lp = localPrivate ?: return ""
        // P-256 的私钥是 EC，X25519 的私钥算法名是 XDH/X25519
        val name = lp.algorithm.uppercase()
        return if (name.contains("X25519") || name.contains("XDH")) "X25519" else "P256"
    }

    /** 本机公钥（Base64），上传给服务器供对端取用 */
    fun publicKeyBase64(): String {
        val spki = rawPub?.let { wrapX25519Spki(it) }
            ?: localPublic?.encoded
            ?: throw IllegalStateException("聊天密钥尚未初始化")
        return Base64.encodeToString(spki, Base64.NO_WRAP)
    }

    /** 与对端公钥协商出 32 字节会话密钥 */
    fun deriveSessionKey(peerPublicKeyB64: String): ByteArray {
        val peerSpki = Base64.decode(peerPublicKeyB64, Base64.NO_WRAP)
        val priv = rawPriv

        val shared: ByteArray = if (priv != null) {
            // 原生 X25519：对端必须是 X25519 公钥，否则曲线不同算不出结果
            val peerRaw = extractX25519Spki(peerSpki)
                ?: throw IllegalStateException(
                    "对端公钥不是 X25519，无法与本机（X25519）协商：" +
                    "对方需升级到 X25519 版本"
                )
            EchTls.x25519Shared(priv, peerRaw)
                ?: throw IllegalStateException("X25519 协商失败（对端公钥非法）")
        } else {
            // JCA 通道：X25519（系统）或 P-256
            val peerIsX = isX25519Spki(peerSpki)
            val alg = if (peerIsX) "X25519" else "EC"
            val kf = KeyFactory.getInstance(alg)
            val peerPub: PublicKey = kf.generatePublic(X509EncodedKeySpec(peerSpki))
            val ka = KeyAgreement.getInstance(if (peerIsX) "X25519" else "ECDH")
            ka.init(localPrivate)
            ka.doPhase(peerPub, true)
            ka.generateSecret()
        }

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