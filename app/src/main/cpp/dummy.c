/* 没有 BoringSSL 的 ABI（例如 armeabi-v7a）用的空实现，保证 CMake 不会链接失败 */
#include <jni.h>
JNIEXPORT jbyteArray JNICALL
Java_www_xdyl_hygge_com_EchTls_request(JNIEnv *env, jclass cls, jstring h, jint p, jbyteArray e, jbyteArray r) {
    (void) env; (void) cls; (void) h; (void) p; (void) e; (void) r;
    return NULL;
}
