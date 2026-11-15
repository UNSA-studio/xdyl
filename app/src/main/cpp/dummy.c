/* 没有 BoringSSL 的 ABI 用的空实现，保证 CMake 不会链接失败 */
#include <jni.h>

JNIEXPORT jbyteArray JNICALL
Java_www_xdyl_hygge_com_EchTls_request(JNIEnv *env, jclass cls, jstring h, jint p, jbyteArray e, jbyteArray r) {
    (void) env; (void) cls; (void) h; (void) p; (void) e; (void) r;
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_www_xdyl_hygge_com_EchTls_x25519Generate(JNIEnv *env, jclass cls, jbyteArray a, jbyteArray b) {
    (void) env; (void) cls; (void) a; (void) b;
    return JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_www_xdyl_hygge_com_EchTls_x25519Shared(JNIEnv *env, jclass cls, jbyteArray a, jbyteArray b) {
    (void) env; (void) cls; (void) a; (void) b;
    return NULL;
}