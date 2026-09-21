package com.zz.infisense.camera;

import android.text.TextUtils;
import android.util.Log;

/**
 * JNI bindings for Infiray Tiny1-B UVC capture ({@code libUVCCamera.so}).
 * Native registration is hard-coded to this class name; do not rename or move it.
 * USB host open/permission lives in the product {@code UsbHostController}, not here.
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

    static {
        try {
            System.loadLibrary("jpeg-turbo1500");
            System.loadLibrary("usb100");
            System.loadLibrary("uvc");
            System.loadLibrary("UVCCamera");
            librariesLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            librariesFailed = true;
            Log.e(TAG, "Tiny1-B JNI libraries failed to load (arm64 required)", error);
        }
    }

    public static boolean areLibrariesLoaded() {
        return librariesLoaded && !librariesFailed;
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
            throw new IllegalStateException("Tiny1-B JNI not loaded");
        }
        if (nativePtr == 0) {
            nativePtr = nativeCreate();
        }
    }

    public synchronized boolean connect(UsbHost host) {
        if (nativePtr == 0 || host == null || !host.isOpen()) {
            return false;
        }
        int result = nativeConnect(
                nativePtr,
                host.getVendorId(),
                host.getProductId(),
                host.getFileDescriptor(),
                host.getBusNum(),
                host.getDevNum(),
                usbfsPath(host.getDeviceName())
        );
        if (result != 0) {
            Log.e(TAG, "nativeConnect failed: " + result);
            return false;
        }
        nativeSetPreviewSize(
                nativePtr,
                previewWidth,
                previewHeight,
                DEFAULT_PREVIEW_MIN_FPS,
                DEFAULT_PREVIEW_MAX_FPS,
                FRAME_FORMAT_YUYV,
                DEFAULT_BANDWIDTH
        );
        return true;
    }

    public synchronized void setFrameCallback(IFrameCallback callback) {
        if (nativePtr != 0) {
            nativeSetFrameCallback(nativePtr, callback);
        }
    }

    public synchronized void startPreview() {
        if (nativePtr != 0) {
            nativeStartPreview(nativePtr);
        }
    }

    public synchronized void stopPreview() {
        if (nativePtr != 0) {
            nativeSetFrameCallback(nativePtr, null);
            nativeStopPreview(nativePtr);
        }
    }

    public synchronized void release() {
        if (nativePtr != 0) {
            nativeRelease(nativePtr);
            nativeDestroy(nativePtr);
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
            return builder.toString();
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
