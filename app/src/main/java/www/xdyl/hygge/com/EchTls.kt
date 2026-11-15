package www.xdyl.hygge.com

/**
 * ECH 原生层（BoringSSL）。
 *
 * 服务端已开启 ECH：DNS 的 HTTPS(type 65) 记录会下发 `ech=`（ECHConfigList，实测 71 字节）。
 * 用它把真实域名的 ClientHello 加密，外层 SNI 变成 cloudflare-ech.com，
 * 从而绕过「明文 SNI 被中间设备按域名 RST」的阻断。
 *
 * 只在 arm64 上提供真实实现；其它 ABI 加载到的是空壳（request 返回 null），
 * 调用方需回退到明文通道。
 */
object EchTls {

    @Volatile
    private var ready = false

    init {
        ready = try {
            System.loadLibrary("echjni")
            LogManager.i("[ECH] BoringSSL 原生库已加载")
            true
        } catch (t: Throwable) {
            LogManager.w("[ECH] BoringSSL 原生库加载失败：${t.message}")
            false
        }
    }

    fun available(): Boolean = ready

    /**
     * 建立 ECH-TLS 连接，写入原始 HTTP/1.1 请求，返回服务端原始响应字节。
     *
     * @param host      真实域名（如 nu-pe-chat.cc.cd），也是证书校验和 inner SNI 用的值
     * @param port      端口，一般是 443
     * @param echConfig ECHConfigList（来自 DNS HTTPS 记录）
     * @param rawRequest 完整的 HTTP/1.1 请求文本（含 CRLFCRLF 结尾）
     * @return 服务端响应原始字节；null 表示失败
     */
    external fun request(
        host: String,
        port: Int,
        echConfig: ByteArray,
        rawRequest: ByteArray
    ): ByteArray?

    /**
     * 生成一对 X25519 密钥，写进传入的 32 字节数组。
     *
     * 用途：Android SDK < 33 没有系统 X25519，只能退回 P-256，而 iOS 端是
     * X25519 —— 两条曲线算不出同一个共享密钥，跨端必然解不开。这里直接借
     * BoringSSL（ECH 已经在用）补齐。
     *
     * @return false 表示原生库不可用或随机数失败，调用方应回退
     */
    external fun x25519Generate(privOut: ByteArray, pubOut: ByteArray): Boolean

    /**
     * 用自己的 32 字节私钥和对方 32 字节公钥算共享密钥。
     *
     * @return 32 字节共享密钥；公钥非法（低阶点）或原生库不可用时返回 null
     */
    external fun x25519Shared(priv: ByteArray, peerPub: ByteArray): ByteArray?
}
