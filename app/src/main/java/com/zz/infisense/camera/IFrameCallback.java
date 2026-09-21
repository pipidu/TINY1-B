package com.zz.infisense.camera;

/**
 * JNI callback required by {@code libUVCCamera.so}.
 * Package and method name must stay as compiled in the native library.
 */
public interface IFrameCallback {
    void onFrame(byte[] frame);
}
