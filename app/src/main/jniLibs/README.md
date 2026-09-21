# Tiny1-B Android UVC JNI

These files are **SDK glue required to open Infiray Tiny1-B**, not the vendor demo app.

| File | Role |
|------|------|
| `app/src/main/jniLibs/arm64-v8a/libUVCCamera.so` | JNI UVC capture (class `com.zz.infisense.camera.UVCCamera`) |
| `libuvc.so` / `libusb100.so` / `libjpeg-turbo1500.so` | Native dependencies of `libUVCCamera` |
| `com.zz.infisense.camera.UVCCamera` | Java native method holders (names fixed by the `.so`) |
| `com.zz.infisense.camera.IFrameCallback` | Frame callback interface expected by the `.so` |

Do **not** add the demo `MainActivity`, layouts, APKs, Windows `libir_sample`, or the original zip.

Only **arm64-v8a** is provided by the vendor Android stack used here.
