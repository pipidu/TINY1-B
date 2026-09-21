# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

## Vendor demo is reference-only (mandatory)

- The vendor zip is a **private reference**. Download and inspect it outside the repo.
- **Do not commit** the zip, extracted demo project, demo APKs, sample assets, `libir_sample`, Windows DLLs, or vendor sample app source (`MainActivity`, demo layouts, etc.).
- If any of that lands on `main`, delete it, tighten `.gitignore`, and commit the cleanup to `main`.
- The product may keep **only** the Tiny1-B pieces required to build: arm64 JNI `.so` files (`libUVCCamera`, `libuvc`, `libusb100`, `libjpeg-turbo1500`) plus the JNI Java class names those libraries bind to.
- JNI symbols are compiled as `com.zz.infisense.camera.UVCCamera` / `IFrameCallback`. Those two classes are SDK glue, not the demo UI.

## Current status

- JNI stack dropped under `app/src/main/jniLibs/arm64-v8a/` with rewritten `UVCCamera` / `IFrameCallback` holders.
- App Gradle scaffold, imaging ISR, measurement UI still to land.
- Device path (from private demo study, not shipped): UVC **VID `0x0BDA` / PID `0x3901`**, stream **256×384** YUYV, split into **256×192** image + **256×192** temperature, rotate 90° CCW for portrait. Temperature is `uint16` little-endian, **°C = raw/16 − 273.15**.

## Tiny1-B integration

1. USB host finds VID/PID `0x0BDA`/`0x3901`, requests permission, opens a file descriptor.
2. `UVCCamera.connect` passes fd/bus/dev to `libUVCCamera`; preview size **256×384** YUYV.
3. Each callback frame is split in half: image YUYV then temperature plane (see `core` `FrameParser` once added).
4. ISP commands (shutter, KB cal, max shutter interval) use USB control transfers on the UVC control interface — product code, not demo UI.

## ISR / measurement

Not implemented in tree yet. Target: software 2×/4× fusion of temperature AGC + Y-detail, min/max markers, center + user points.

## Layout

```
AGENTS.md
README.md
.gitignore
app/src/main/jniLibs/arm64-v8a/*.so     Tiny1-B UVC JNI (required)
app/src/main/java/com/zz/infisense/camera/   JNI class names required by .so
```

## Build / run

Not fully scaffolded. After Gradle lands:

```bash
./gradlew :core:test
./gradlew :app:assembleDebug
```

Arm64 Android phone + USB-OTG + Tiny1-B. Emulators cannot load these `.so` files.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
