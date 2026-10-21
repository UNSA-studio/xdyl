/*
 * ECH 客户端（BoringSSL）
 *
 * 目的：解决“明文 SNI 被中间设备按域名匹配后 RST”的问题。
 * 用 ECHConfig 把真实域名的 ClientHello 加密，外层 SNI 显示为 Cloudflare 的
 * public_name（cloudflare-ech.com），从而绕过 SNI 阻断。
 *
 * 只做一件事：建立一条 ECH-TLS 连接，写入一个原始 HTTP/1.1 请求，读回全部响应。
 * 上层（Kotlin）负责组包和解析。
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <pthread.h>

#include <openssl/ssl.h>
#include <openssl/err.h>

#define TAG "EchJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* TLS 会话复用：免掉每次请求的完整握手（否则每请求多 300~500ms） */
static pthread_mutex_t g_sess_lock = PTHREAD_MUTEX_INITIALIZER;
static SSL_SESSION *g_sess = NULL;
static char g_sess_host[256] = {0};

static int tcp_connect(const char *host, int port) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    char ps[16];
    snprintf(ps, sizeof(ps), "%d", port);

    struct addrinfo *res = NULL;
    if (getaddrinfo(host, ps, &hints, &res) != 0) {
        return -1;
    }

    int fd = -1;
    for (struct addrinfo *p = res; p != NULL; p = p->ai_next) {
        fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) continue;
        if (connect(fd, p->ai_addr, p->ai_addrlen) == 0) break;
        close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    return fd;
}

JNIEXPORT jbyteArray JNICALL
Java_www_xdyl_hygge_com_EchTls_request(JNIEnv *env, jclass cls,
                                       jstring jhost, jint port,
                                       jbyteArray jech, jbyteArray jreq) {
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    if (host == NULL) return NULL;

    jsize echLen = jech ? (*env)->GetArrayLength(env, jech) : 0;
    jbyte *ech = echLen > 0 ? (*env)->GetByteArrayElements(env, jech, NULL) : NULL;
    jsize reqLen = jreq ? (*env)->GetArrayLength(env, jreq) : 0;
    jbyte *req = reqLen > 0 ? (*env)->GetByteArrayElements(env, jreq, NULL) : NULL;

    jbyteArray out = NULL;
    SSL_CTX *ctx = NULL;
    SSL *ssl = NULL;
    int fd = -1;
    unsigned char *rbuf = NULL;

    do {
        ctx = SSL_CTX_new(TLS_client_method());
        if (ctx == NULL) { LOGE("SSL_CTX_new failed"); break; }

        SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
        /* Android 的系统 CA 目录，BoringSSL 不会自己找，必须手动加载 */
        if (!SSL_CTX_load_verify_locations(ctx, NULL, "/system/etc/security/cacerts")) {
            SSL_CTX_set_default_verify_paths(ctx);
        }
        SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);

        fd = tcp_connect(host, (int) port);
        if (fd < 0) { LOGE("tcp connect failed %s:%d", host, (int) port); break; }

        ssl = SSL_new(ctx);
        if (ssl == NULL) { LOGE("SSL_new failed"); break; }
        SSL_set_fd(ssl, fd);

        pthread_mutex_lock(&g_sess_lock);
        if (g_sess != NULL && strcmp(g_sess_host, host) == 0) {
            SSL_set_session(ssl, g_sess);
        }
        pthread_mutex_unlock(&g_sess_lock);

        /* 真实域名：ECH 被接受时该值在加密的 inner ClientHello 里 */
        SSL_set_tlsext_host_name(ssl, host);
        SSL_set1_host(ssl, host);

        if (ech != NULL && echLen > 0) {
            if (SSL_set1_ech_config_list(ssl, (const uint8_t *) ech, (size_t) echLen) == 1) {
                LOGI("ECH enabled (%d bytes config)", (int) echLen);
            } else {
                LOGE("ECH config rejected, fallback to plain SNI");
            }
        }

        int rc = SSL_connect(ssl);
        if (rc != 1) {
            int e = SSL_get_error(ssl, rc);
            unsigned long pe = ERR_get_error();
            LOGE("SSL_connect failed rc=%d err=%d(%s) verify=%ld",
                 rc, e, ERR_reason_error_string(pe) ? ERR_reason_error_string(pe) : "?",
                 SSL_get_verify_result(ssl));
            break;
        }
        LOGI("handshake ok: %s%s", SSL_get_version(ssl), SSL_session_reused(ssl) ? " (resumed)" : "");

        SSL_SESSION *ns = SSL_get1_session(ssl);
        if (ns != NULL) {
            pthread_mutex_lock(&g_sess_lock);
            if (g_sess != NULL) SSL_SESSION_free(g_sess);
            g_sess = ns;
            strncpy(g_sess_host, host, sizeof(g_sess_host) - 1);
            g_sess_host[sizeof(g_sess_host) - 1] = 0;
            pthread_mutex_unlock(&g_sess_lock);
        }

        if (req != NULL && reqLen > 0) {
            int off = 0;
            while (off < reqLen) {
                int n = SSL_write(ssl, req + off, reqLen - off);
                if (n <= 0) { LOGE("SSL_write failed"); break; }
                off += n;
            }
        }

        size_t cap = 65536, len = 0;
        rbuf = (unsigned char *) malloc(cap);
        if (rbuf == NULL) break;

        for (;;) {
            if (len + 4096 > cap) {
                cap *= 2;
                unsigned char *t = (unsigned char *) realloc(rbuf, cap);
                if (t == NULL) break;
                rbuf = t;
            }
            int n = SSL_read(ssl, rbuf + len, (int) (cap - len));
            if (n <= 0) break;
            len += (size_t) n;
        }

        out = (*env)->NewByteArray(env, (jsize) len);
        if (out != NULL && len > 0) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) rbuf);
        }
    } while (0);

    if (rbuf != NULL) free(rbuf);
    if (ssl != NULL) SSL_free(ssl);
    if (fd >= 0) close(fd);
    if (ctx != NULL) SSL_CTX_free(ctx);

    if (req != NULL) (*env)->ReleaseByteArrayElements(env, jreq, req, JNI_ABORT);
    if (ech != NULL) (*env)->ReleaseByteArrayElements(env, jech, ech, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jhost, host);

    return out;
}
