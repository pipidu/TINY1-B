package com.zz.infisense.camera;

import android.text.TextUtils;
import android.util.Log;

/**
 * JNI bindings for Infiray Tiny1-B UVC capture ({@code libUVCCamera.so}).
 * Package name is fixed by the native library. All native calls are guarded so a
 * failure becomes a Java error instead of taking down the process when possible.
 */
public class UVCCamera {
    private static final String TAG = "UVCCamera";
    private static final String DEFAULT_USBFS = "/dev/bus/usb";

    public static final int FRAME_FORMAT_YUYV = 0;
    public static final int DEFAULT_PREVIEW_MIN_FPS = 1;
    public static final int DEFAULT_PREVIEW_MAX_FPS = 31;
    public static final float DEFAULT_BANDWIDTH = 1.0f;

    public interface UsbHost {
        int getVendorId();
        int getProductId();
        int getFileDescriptor();
        int getBusNum();
        int getDevNum();
        String getDeviceName();
        boolean isOpen();
    }

    private static boolean librariesLoaded;
    private static boolean librariesFailed;
    private static String loadError;

    static {
        try {
            System.loadLibrary("jpeg-turbo1500");
            System.loadLibrary("usb100");
            System.loadLibrary("uvc");
            System.loadLibrary("UVCCamera");
            librariesLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            librariesFailed = true;
            loadError = error.getMessage();
            Log.e(TAG, "Tiny1-B JNI libraries failed to load (arm64 required)", error);
        } catch (Throwable error) {
            librariesFailed = true;
            loadError = error.getMessage();
            Log.e(TAG, "Tiny1-B JNI init failed", error);
        }
    }

    public static boolean areLibrariesLoaded() {
        return librariesLoaded && !librariesFailed;
    }

    public static String getLoadError() {
        return loadError;
    }

    private long nativePtr;
    private final int previewWidth;
    private final int previewHeight;

    public UVCCamera(int previewWidth, int previewHeight) {
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
    }

    public synchronized void create() {
        if (!areLibrariesLoaded()) {
            throw new IllegalStateException(
                    loadError != null ? loadError : "Tiny1-B JNI 未加载，请使用 ARM64 真机");
        }
        if (nativePtr == 0) {
            nativePtr = nativeCreate();
        }
        if (nativePtr == 0) {
            throw new IllegalStateException("nativeCreate 返回空指针");
        }
    }

    public synchronized boolean connect(UsbHost host) {
        try {
            if (nativePtr == 0 || host == null || !host.isOpen()) {
                return false;
            }
            int fd = host.getFileDescriptor();
            if (fd <= 0) {
                Log.e(TAG, "connect: invalid file descriptor " + fd);
                return false;
            }
            String usbfs = usbfsPath(host.getDeviceName());
            int result = nativeConnect(
                    nativePtr,
                    host.getVendorId(),
                    host.getProductId(),
                    fd,
                    host.getBusNum(),
                    host.getDevNum(),
                    usbfs
            );
            if (result != 0) {
                Log.e(TAG, "nativeConnect failed: " + result);
                return false;
            }
            int sizeResult = nativeSetPreviewSize(
                    nativePtr,
                    previewWidth,
                    previewHeight,
                    DEFAULT_PREVIEW_MIN_FPS,
                    DEFAULT_PREVIEW_MAX_FPS,
                    FRAME_FORMAT_YUYV,
                    DEFAULT_BANDWIDTH
            );
            if (sizeResult != 0) {
                Log.e(TAG, "nativeSetPreviewSize failed: " + sizeResult);
                return false;
            }
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "connect threw", error);
            return false;
        }
    }

    public synchronized void setFrameCallback(IFrameCallback callback) {
        try {
            if (nativePtr != 0) {
                nativeSetFrameCallback(nativePtr, callback);
            }
        } catch (Throwable error) {
            Log.e(TAG, "setFrameCallback", error);
        }
    }

    public synchronized boolean startPreview() {
        try {
            if (nativePtr == 0) {
                return false;
            }
            int result = nativeStartPreview(nativePtr);
            return result == 0;
        } catch (Throwable error) {
            Log.e(TAG, "startPreview", error);
            return false;
        }
    }

    public synchronized void stopPreview() {
        try {
            if (nativePtr != 0) {
                nativeSetFrameCallback(nativePtr, null);
                nativeStopPreview(nativePtr);
            }
        } catch (Throwable error) {
            Log.e(TAG, "stopPreview", error);
        }
    }

    public synchronized void release() {
        try {
            if (nativePtr != 0) {
                nativeSetFrameCallback(nativePtr, null);
                nativeStopPreview(nativePtr);
                nativeRelease(nativePtr);
                nativeDestroy(nativePtr);
            }
        } catch (Throwable error) {
            Log.e(TAG, "release", error);
        } finally {
            nativePtr = 0;
        }
    }

    private static String usbfsPath(String deviceName) {
        String[] parts = !TextUtils.isEmpty(deviceName) ? deviceName.split("/") : null;
        if (parts != null && parts.length > 2) {
            StringBuilder builder = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length - 2; i++) {
                builder.append('/').append(parts[i]);
            }
            String path = builder.toString();
            return TextUtils.isEmpty(path) ? DEFAULT_USBFS : path;
        }
        return DEFAULT_USBFS;
    }

    private native long nativeCreate();
    private native void nativeDestroy(long idCamera);
    private native int nativeConnect(long idCamera, int vendorId, int productId, int fileDescriptor,
                                     int busNum, int devAddr, String usbfs);
    private static native int nativeRelease(long idCamera);
    private static native int nativeSetPreviewSize(long idCamera, int width, int height,
                                                   int minFps, int maxFps, int mode, float bandwidth);
    private static native String nativeGetSupportedSize(long idCamera);
    private static native int nativeStartPreview(long idCamera);
    private static native int nativeStopPreview(long idCamera);
    private static native int nativeSetFrameCallback(long idCamera, IFrameCallback callback);
}
