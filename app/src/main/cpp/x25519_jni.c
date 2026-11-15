/*
 * ============================================================================
 *  X25519（BoringSSL）
 *
 *  为什么客户端要自己做这个：Android 系统在 SDK < 33 时没有 X25519 实现，
 *  只能退回 P-256。而 iOS 侧用的是 X25519 —— 两种曲线数学上算不出同一个
 *  共享密钥，跨端必然解不开。BoringSSL 已经在链接里（ECH 用），直接用它的
 *  X25519 实现即可，不需要额外依赖。
 *
 *  私钥格式：32 字节原始标量。
 *  公钥格式：32 字节原始 u 坐标。
 * ============================================================================
 */
#include <jni.h>
#include <android/log.h>
#include <openssl/curve25519.h>
#include <openssl/rand.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "echjni", __VA_ARGS__)

/** 生成一对 X25519 密钥。成功返回 true。 */
JNIEXPORT jboolean JNICALL
Java_www_xdyl_hygge_com_EchTls_x25519Generate(JNIEnv *env, jclass cls,
                                              jbyteArray jpriv, jbyteArray jpub) {
    (void) cls;
    unsigned char priv[32];
    unsigned char pub[32];

    if (RAND_bytes(priv, sizeof(priv)) != 1) return JNI_FALSE;
    if (!X25519_public_from_private(pub, priv)) return JNI_FALSE;

    (*env)->SetByteArrayRegion(env, jpriv, 0, 32, (const jbyte *) priv);
    (*env)->SetByteArrayRegion(env, jpub,  0, 32, (const jbyte *) pub);
    return JNI_TRUE;
}

/**
 * 用自己的私钥 + 对方公钥算共享密钥（32 字节）。
 * 任一参数为 NULL 或长度不对返回 NULL。全零输出（非法公钥）也返回 NULL。
 */
JNIEXPORT jbyteArray JNICALL
Java_www_xdyl_hygge_com_EchTls_x25519Shared(JNIEnv *env, jclass cls,
                                            jbyteArray jpriv, jbyteArray jpeer) {
    (void) cls;
    if (jpriv == NULL || jpeer == NULL) return NULL;
    if ((*env)->GetArrayLength(env, jpriv) != 32) return NULL;
    if ((*env)->GetArrayLength(env, jpeer) != 32) return NULL;

    unsigned char priv[32];
    unsigned char peer[32];
    unsigned char out[32];

    (*env)->GetByteArrayRegion(env, jpriv, 0, 32, (jbyte *) priv);
    (*env)->GetByteArrayRegion(env, jpeer, 0, 32, (jbyte *) peer);

    if (!X25519(out, priv, peer)) {
        LOGE("X25519 协商失败（公钥非法，通常是低阶点）");
        return NULL;
    }

    // 全零结果表示协商失败（RFC 7748 §6.1），必须丢弃
    int allZero = 1;
    for (int i = 0; i < 32; i++) if (out[i] != 0) { allZero = 0; break; }
    if (allZero) {
        LOGE("X25519 协商结果全零，拒绝使用");
        return NULL;
    }

    jbyteArray res = (*env)->NewByteArray(env, 32);
    if (res != NULL) (*env)->SetByteArrayRegion(env, res, 0, 32, (const jbyte *) out);
    return res;
}