package com.zz.infisense.camera;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.text.TextUtils;
import android.util.Log;
import android.os.Handler;


public class UVCCamera {
    private static final boolean DEBUG = true;    // TODO set false when releasing
    private static final String TAG = "UVCCamera";
    private static final String DEFAULT_USBFS = "/dev/bus/usb";

    public static final int FRAME_FORMAT_YUYV = 0;
    public static final int FRAME_FORMAT_MJPEG = 1;

    public static final int DEFAULT_PREVIEW_WIDTH = 640;
    public static final int DEFAULT_PREVIEW_HEIGHT = 480;
    public static final int DEFAULT_PREVIEW_MODE = FRAME_FORMAT_YUYV;
    public static final int DEFAULT_PREVIEW_MIN_FPS = 1;
    public static final int DEFAULT_PREVIEW_MAX_FPS = 31;
    public static final float DEFAULT_BANDWIDTH = 1.0f;

    private int vid;
    private int pid;
    private boolean openStatus;

    private static boolean isLoaded;
    static {
        if (!isLoaded) {
            System.loadLibrary("jpeg-turbo1500");
            System.loadLibrary("usb100");
            System.loadLibrary("uvc");
            System.loadLibrary("UVCCamera");
            isLoaded = true;
        }
    }

    private UsbControlBlock mCtrlBlock;
    protected int mCurrentWidth = DEFAULT_PREVIEW_WIDTH;
    protected int mCurrentHeight = DEFAULT_PREVIEW_HEIGHT;
    protected long mNativePtr;

    public UVCCamera(int vid, int pid, int width, int height, Context context, Handler handler) {
        this.vid = vid;
        this.pid = pid;
        mCurrentWidth = width;
        mCurrentHeight = height;
        mCtrlBlock = new UsbControlBlock(context, handler);
    }

    public void create() {
        mNativePtr = nativeCreate();
    }

    public boolean open() {
        if (mCtrlBlock == null) {
            Log.w(TAG, "open: mCtrlBlock == null");
        }
        boolean result = mCtrlBlock.getUsbCamera(vid, pid);         //SHIDL
        if (result == true) {
            nativeConnect(mNativePtr, mCtrlBlock.getVenderId(), mCtrlBlock.getProductId(), mCtrlBlock.getFileDescriptor(),
                    mCtrlBlock.getBusNum(), mCtrlBlock.getDevNum(), getUSBFSName(mCtrlBlock));

            nativeSetPreviewSize(mNativePtr, mCurrentWidth, mCurrentHeight, DEFAULT_PREVIEW_MIN_FPS, DEFAULT_PREVIEW_MAX_FPS, DEFAULT_PREVIEW_MODE, DEFAULT_BANDWIDTH);
        }
        return result;
    }

    public boolean check() {
        if (mCtrlBlock == null) {
            Log.w(TAG, "open: mCtrlBlock == null");
        }
        return mCtrlBlock.getUsbCamera(vid, pid);
    }

    public void setOpenStatus(boolean openStatus) {
        this.openStatus = openStatus;
    }

    public boolean getOpenStatus() {
        return openStatus;
    }

    public void close() {
        if (mNativePtr != 0) {
            nativeRelease(mNativePtr);
        }
        if (mCtrlBlock != null) {
            mCtrlBlock.close();
            mCtrlBlock = null;
        }
        if (DEBUG) Log.v(TAG, "close:finished");
    }

    public UsbDevice getDevice() {
        return mCtrlBlock != null ? mCtrlBlock.getDevice() : null;
    }

    public String getDeviceName(){
        return mCtrlBlock != null ? mCtrlBlock.getDeviceName() : null;
    }

    public UsbControlBlock getUsbControlBlock() {
        return mCtrlBlock;
    }

    public void setFrameCallback(final IFrameCallback callback) {
        if (mNativePtr != 0) {
            nativeSetFrameCallback(mNativePtr, callback);
        }
    }

    public synchronized void startPreview() {
        if (mCtrlBlock != null) {
            nativeStartPreview(mNativePtr);
        }
    }

    public synchronized void stopPreview() {
        setFrameCallback(null);
        if (mCtrlBlock != null) {
            nativeStopPreview(mNativePtr);
        }
    }

    public synchronized void destroy() {
        close();
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    public void setKbCalibrateValid() {
        mCtrlBlock.setIrCameraKbCalibrateValid();
    }

    public void setKbCalibrateInvalid() {
        mCtrlBlock.setIrCameraKbCalibrateInvalid();
    }

    public void manualShut() {
        mCtrlBlock.irCameraManualShut();
    }

    public int getShutterMaxTime() {
        return mCtrlBlock.getShutterMaxTime();
    }

    public void setShutterMaxTime(byte maxTime) {
        mCtrlBlock.setShutterMaxTime(maxTime);
    }

    private final String getUSBFSName(final UsbControlBlock ctrlBlock) {
        String result = null;
        final String name = ctrlBlock.getDeviceName();
        final String[] v = !TextUtils.isEmpty(name) ? name.split("/") : null;
        if ((v != null) && (v.length > 2)) {
            final StringBuilder sb = new StringBuilder(v[0]);
            for (int i = 1; i < v.length - 2; i++)
                sb.append("/").append(v[i]);
            result = sb.toString();
        }
        if (TextUtils.isEmpty(result)) {
            Log.w(TAG, "failed to get USBFS path, try to use default path:" + name);
            result = DEFAULT_USBFS;
        }
        return result;
    }

    private final native long nativeCreate();
    private final native void nativeDestroy(final long id_camera);

    private final native int nativeConnect(long id_camera, int venderId, int productId, int fileDescriptor, int busNum, int devAddr, String usbfs);
    private static final native int nativeRelease(final long id_camera);

    private static final native int nativeSetPreviewSize(final long id_camera, final int width, final int height, final int min_fps, final int max_fps, final int mode, final float bandwidth);
    private static final native String nativeGetSupportedSize(final long id_camera);
    private static final native int nativeStartPreview(final long id_camera);
    private static final native int nativeStopPreview(final long id_camera);
    private static final native int nativeSetFrameCallback(final long mNativePtr, final IFrameCallback callback);
}
