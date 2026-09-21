package com.zz.infisense.camera;

import android.text.TextUtils;
import android.util.Log;

/**
 * JNI bindings for Infiray Tiny1-B UVC capture ({@code libUVCCamera.so}).
 * Package, native method names, and {@code mNativePtr} must match the .so
 * (GetFieldID "mNativePtr" "J"). Field names follow the vendor demo wrapper.
 */
public class UVCCamera {
    private static final String TAG = "UVCCamera";
    private static final String DEFAULT_USBFS = "/dev/bus/usb";

    public static final int FRAME_FORMAT_YUYV = 0;
    public static final int FRAME_FORMAT_MJPEG = 1;
    public static final int DEFAULT_PREVIEW_WIDTH = 256;
    public static final int DEFAULT_PREVIEW_HEIGHT = 384;
    public static final int DEFAULT_PREVIEW_MODE = FRAME_FORMAT_YUYV;
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
    /** Same name as the demo; nativeCreate looks this up if present. */
    private static boolean isLoaded;

    static {
        try {
            System.loadLibrary("jpeg-turbo1500");
            System.loadLibrary("usb100");
            System.loadLibrary("uvc");
            System.loadLibrary("UVCCamera");
            librariesLoaded = true;
            isLoaded = true;
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

    // Instance field names/types match the demo UVCCamera.java the .so was built against.
    private int vid;
    private int pid;
    private boolean openStatus;
    protected int mCurrentWidth = DEFAULT_PREVIEW_WIDTH;
    protected int mCurrentHeight = DEFAULT_PREVIEW_HEIGHT;
    /** Native GetFieldID(UVCCamera, "mNativePtr", "J") — must not be renamed. */
    protected long mNativePtr;

    public UVCCamera(int previewWidth, int previewHeight) {
        this.mCurrentWidth = previewWidth;
        this.mCurrentHeight = previewHeight;
    }

    public synchronized void create() {
        if (!areLibrariesLoaded()) {
            throw new IllegalStateException(
                    loadError != null ? loadError : "Tiny1-B JNI 未加载，请使用 ARM64 真机");
        }
        if (mNativePtr == 0) {
            mNativePtr = nativeCreate();
        }
        if (mNativePtr == 0) {
            throw new IllegalStateException("nativeCreate 返回空指针");
        }
    }

    public synchronized boolean connect(UsbHost host) {
        try {
            if (mNativePtr == 0 || host == null || !host.isOpen()) {
                return false;
            }
            int fd = host.getFileDescriptor();
            if (fd <= 0) {
                Log.e(TAG, "connect: invalid file descriptor " + fd);
                return false;
            }
            vid = host.getVendorId();
            pid = host.getProductId();
            String usbfs = usbfsPath(host.getDeviceName());
            int result = nativeConnect(
                    mNativePtr,
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
                    mNativePtr,
                    mCurrentWidth,
                    mCurrentHeight,
                    DEFAULT_PREVIEW_MIN_FPS,
                    DEFAULT_PREVIEW_MAX_FPS,
                    FRAME_FORMAT_YUYV,
                    DEFAULT_BANDWIDTH
            );
            if (sizeResult != 0) {
                Log.e(TAG, "nativeSetPreviewSize failed: " + sizeResult);
                return false;
            }
            openStatus = true;
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "connect threw", error);
            return false;
        }
    }

    public synchronized void setFrameCallback(IFrameCallback callback) {
        try {
            if (mNativePtr != 0) {
                nativeSetFrameCallback(mNativePtr, callback);
            }
        } catch (Throwable error) {
            Log.e(TAG, "setFrameCallback", error);
        }
    }

    public synchronized boolean startPreview() {
        try {
            if (mNativePtr == 0) {
                return false;
            }
            int result = nativeStartPreview(mNativePtr);
            return result == 0;
        } catch (Throwable error) {
            Log.e(TAG, "startPreview", error);
            return false;
        }
    }

    public synchronized void stopPreview() {
        try {
            if (mNativePtr != 0) {
                nativeSetFrameCallback(mNativePtr, null);
                nativeStopPreview(mNativePtr);
            }
        } catch (Throwable error) {
            Log.e(TAG, "stopPreview", error);
        }
    }

    public synchronized void release() {
        try {
            if (mNativePtr != 0) {
                nativeSetFrameCallback(mNativePtr, null);
                nativeStopPreview(mNativePtr);
                nativeRelease(mNativePtr);
                nativeDestroy(mNativePtr);
            }
        } catch (Throwable error) {
            Log.e(TAG, "release", error);
        } finally {
            mNativePtr = 0;
            openStatus = false;
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
    private native void nativeDestroy(long id_camera);
    private native int nativeConnect(long id_camera, int venderId, int productId, int fileDescriptor,
                                     int busNum, int devAddr, String usbfs);
    private static native int nativeRelease(long id_camera);
    private static native int nativeSetPreviewSize(long id_camera, int width, int height,
                                                   int min_fps, int max_fps, int mode, float bandwidth);
    private static native String nativeGetSupportedSize(long id_camera);
    private static native int nativeStartPreview(long id_camera);
    private static native int nativeStopPreview(long id_camera);
    private static native int nativeSetFrameCallback(long mNativePtr, IFrameCallback callback);
}
