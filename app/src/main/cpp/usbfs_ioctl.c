#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/ioctl.h>
#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_DISCONNECT
#define USBDEVFS_DISCONNECT _IO('U', 22)
#endif

static void *direct(JNIEnv *env, jobject buf) {
    if (buf == NULL) {
        return NULL;
    }
    return (*env)->GetDirectBufferAddress(env, buf);
}

JNIEXPORT jlong JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeAddress(JNIEnv *env, jclass cls, jobject buf) {
    (void) cls;
    return (jlong) (intptr_t) direct(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeSubmit(JNIEnv *env, jclass cls, jint fd, jobject urb) {
    (void) cls;
    void *ptr = direct(env, urb);
    if (ptr == NULL) {
        return -EINVAL;
    }
    int rc = ioctl(fd, USBDEVFS_SUBMITURB, ptr);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jlong JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeReap(JNIEnv *env, jclass cls, jint fd, jint timeout_ms) {
    (void) env;
    (void) cls;
    if (timeout_ms >= 0) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLOUT | POLLWRNORM | POLLIN | POLLERR | POLLHUP;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr == 0) {
            return -ETIMEDOUT;
        }
        if (pr < 0) {
            return -errno;
        }
    }
    void *urb = NULL;
    int request = timeout_ms >= 0 ? USBDEVFS_REAPURBNDELAY : USBDEVFS_REAPURB;
    int rc = ioctl(fd, request, &urb);
    if (rc < 0) {
        return -errno;
    }
    return (jlong) (intptr_t) urb;
}

JNIEXPORT jint JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeDiscard(JNIEnv *env, jclass cls, jint fd, jobject urb) {
    (void) cls;
    void *ptr = direct(env, urb);
    if (ptr == NULL) {
        return -EINVAL;
    }
    int rc = ioctl(fd, USBDEVFS_DISCARDURB, ptr);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeClearHalt(JNIEnv *env, jclass cls, jint fd, jint endpoint) {
    (void) env;
    (void) cls;
    unsigned int ep = (unsigned int) (endpoint & 0xFF);
    int rc = ioctl(fd, USBDEVFS_CLEAR_HALT, &ep);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeDisconnect(JNIEnv *env, jclass cls, jint fd, jint interface_number) {
    (void) env;
    (void) cls;
    struct usbdevfs_ioctl cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = interface_number;
    cmd.ioctl_code = USBDEVFS_DISCONNECT;
    cmd.data = NULL;
    int rc = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    return rc < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_com_pipidu_tiny1b_device_Usbfs_nativeClaimInterface(JNIEnv *env, jclass cls, jint fd, jint interface_number) {
    (void) env;
    (void) cls;
    unsigned int ifno = (unsigned int) interface_number;
    int rc = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifno);
    return rc < 0 ? -errno : 0;
}
