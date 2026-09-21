# JNI wrappers for libUVCCamera. Minify is off; keep these if it is ever enabled.
-keep class com.zz.infisense.camera.** { *; }
-keepclassmembers class com.zz.infisense.camera.UVCCamera {
    protected long mNativePtr;
}
