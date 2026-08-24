#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <unistd.h>

#include "hev-main.h"

enum tunnel_state {
    TUNNEL_STOPPED = 0,
    TUNNEL_STARTING,
    TUNNEL_RUNNING,
    TUNNEL_STOPPING,
};

static atomic_int tunnel_state = ATOMIC_VAR_INIT(TUNNEL_STOPPED);

JNIEXPORT jint JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_startTun2socksNative(
    JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd)
{
    const char *path = (*env)->GetStringUTFChars(env, config_path, 0);
    if (path == 0) {
        close(tun_fd);
        return -1;
    }

    int expected = TUNNEL_STOPPED;
    if (!atomic_compare_exchange_strong(&tunnel_state, &expected,
                                        TUNNEL_STARTING)) {
        (*env)->ReleaseStringUTFChars(env, config_path, path);
        close(tun_fd);
        return -2;
    }

    expected = TUNNEL_STARTING;
    atomic_compare_exchange_strong(&tunnel_state, &expected, TUNNEL_RUNNING);

    int result = hev_socks5_tunnel_main_from_file(path, tun_fd);
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    close(tun_fd);
    atomic_store(&tunnel_state, TUNNEL_STOPPED);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_stopTun2socksNative(
    JNIEnv *env, jobject thiz)
{
    int current = atomic_load(&tunnel_state);
    for (;;) {
        if (current == TUNNEL_STOPPED) {
            return JNI_FALSE;
        }
        if (current == TUNNEL_STOPPING) {
            return JNI_TRUE;
        }
        if (atomic_compare_exchange_weak(&tunnel_state, &current,
                                         TUNNEL_STOPPING)) {
            break;
        }
    }

    hev_socks5_tunnel_quit();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_isTun2socksRunningNative(
    JNIEnv *env, jobject thiz)
{
    return atomic_load(&tunnel_state) == TUNNEL_STOPPED ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jlongArray JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_getTun2socksStatsNative(
    JNIEnv *env, jobject thiz)
{
    size_t tx_packets = 0;
    size_t tx_bytes = 0;
    size_t rx_packets = 0;
    size_t rx_bytes = 0;
    jlong values[4];

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result == 0) {
        return 0;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
