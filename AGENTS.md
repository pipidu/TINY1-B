# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

## Vendor demo is reference-only (mandatory)

- The vendor zip is a **private reference**. Download and inspect it outside the repo.
- **Do not commit** the zip, extracted demo project, demo APKs, sample assets, `libir_sample`, Windows DLLs, or vendor sample app source (`MainActivity`, demo layouts, etc.).
- If any of that lands on `main`, delete it, tighten `.gitignore`, and commit the cleanup to `main`.
- The product may keep **only** the Tiny1-B pieces required to build: arm64 JNI `.so` files (`libUVCCamera`, `libuvc`, `libusb100`, `libjpeg-turbo1500`) plus the JNI Java class names those libraries bind to.
- JNI symbols are compiled as `com.zz.infisense.camera.UVCCamera` / `IFrameCallback`. Those two classes are SDK glue, not the demo UI.

## Current status

Shipped on `main`: Compose app, USB session, ISR, palettes, min/max + center + user points. No vendor demo tree in git.

## Current architecture

```
core/     Pure JVM: UVC frame split, temperature, palettes, software ISR, measurement
app/      Android: USB host, UVC session, Compose UI (Chinese)
```

- `ThermalEngine` opens Tiny1-B, pulls YUYV frames, runs ISR, publishes `EngineState`.
- `LiveViewScreen` is connect-or-live: empty/permission/error cards, thermal stage, palettes, measurement dock.
- `SettingsScreen` covers ISR, min/max, center point, shutter interval, KB cal, sample preview.

## Tiny1-B integration

1. USB host matches **VID `0x0BDA` / PID `0x3901`** (`device_filter.xml` decimal 3034/14593).
2. After permission, `UsbHostController` opens a connection; `UVCCamera.connect` passes fd/bus/dev into `libUVCCamera`.
3. Preview size **256×384** YUYV. Each frame is split: **256×192** image + **256×192** Kelvin-16 temperature, then rotated 90° CCW to **192×256** portrait (`FrameParser`).
4. Temperature: `°C = raw/16 − 273.15`.
5. Control transfers (`Tiny1BCommands`): manual shutter `0x0345`, KB cal `0x0341`, shutter max get `0x038A` / set `0x03C4`.

## ISR

`SuperResolution`: percentile-AGC on temperature, Catmull-Rom 2× (optional second pass for 4×), unsharp Y-detail fused into the temperature field, then palette LUT. **Measurement always samples the native 192×256 temperature grid**, not the upscaled pixels.

## Measurement

- Auto **max / min** markers on the live image.
- Always-on **center** point (toggle in settings).
- User points: tap add, drag move, long-press or dock delete, max 8.

## Layout

```
AGENTS.md
README.md
.gitignore
settings.gradle.kts / build.gradle.kts / gradle/
core/src/main/kotlin/com/pipidu/tiny1b/core/
app/src/main/java/com/pipidu/tiny1b/          product
app/src/main/java/com/zz/infisense/camera/    JNI names required by .so
app/src/main/jniLibs/arm64-v8a/               Tiny1-B UVC JNI
```

## Build / run

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # or your SDK
# JDK 21
./gradlew :core:test
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an **arm64** phone with USB-OTG + Tiny1-B. Emulators cannot load the JNI `.so` files.

Optional: 设置 → 样例画面, to exercise palettes/ISR/points without hardware. Debug applicationId is `com.pipidu.tiny1b.debug`.

## Hardware-only gaps

- Real Tiny1-B USB attach, permission, UVC stream, shutter click, and KB cal cannot be verified in this environment.
- Only `arm64-v8a` vendor JNI is available.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
