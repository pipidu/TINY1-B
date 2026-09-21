# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

## Vendor demo is reference-only (mandatory)

- The vendor zip is a **private reference**. Download and inspect it outside the repo.
- **Do not commit** the zip, extracted demo project, demo APKs, sample assets, `libir_sample`, Windows DLLs, or vendor sample app source (`MainActivity`, demo layouts, etc.).
- If any of that lands on `main`, delete it, tighten `.gitignore`, and commit the cleanup to `main`.
- The product may keep **only** the Tiny1-B pieces required to build: arm64 JNI `.so` files (`libUVCCamera`, `libuvc`, `libusb100`, `libjpeg-turbo1500`) plus the JNI Java class names those libraries bind to.
- JNI symbols are compiled as `com.zz.infisense.camera.UVCCamera` / `IFrameCallback`. Those two classes are SDK glue, not the demo UI.

## Current status

On `main`: Compose app, USB session, ISR, display denoise (default off), palettes, min/max + center + user points, **in-app GitHub update**. No vendor demo tree in git.

## Current architecture

```
core/     Pure JVM: UVC frame split, temperature, palettes, software ISR, denoise, measurement, version parse
app/      Android: USB host, UVC session, Compose UI (Chinese), GitHub updater
keystore/ Project signing key (required so later APKs overwrite the same install)
```

- `ThermalEngine` opens Tiny1-B, pulls YUYV frames, runs optional denoise + ISR, publishes `EngineState`.
- `LiveViewScreen` is connect-or-live: empty/permission/error cards, thermal stage, palettes, measurement dock.
- `SettingsScreen` covers ISR, 降噪, min/max, center, shutter, KB cal, sample preview, **检查更新**.
- `AppUpdater` queries `https://api.github.com/repos/pipidu/TINY1-B/releases/latest` (user-initiated).

## Tiny1-B integration

1. USB host matches **VID `0x0BDA` / PID `0x3901`** (`device_filter.xml` decimal 3034/14593).
2. After permission, `UsbHostController` opens a connection; `UVCCamera.connect` passes fd/bus/dev into `libUVCCamera`.
3. Preview size **256×384** YUYV. Each frame is split: **256×192** image + **256×192** Kelvin-16 temperature, then rotated 90° CCW to **192×256** portrait (`FrameParser`).
4. Temperature: `°C = raw/16 − 273.15`.
5. Control transfers (`Tiny1BCommands`): manual shutter `0x0345`, KB cal `0x0341`, shutter max get `0x038A` / set `0x03C4`.

## ISR

`SuperResolution`: percentile-AGC on temperature, Catmull-Rom 2× (optional second pass for 4×), unsharp Y-detail fused into the temperature field, then palette LUT. **Measurement always samples the native 192×256 temperature grid**, not the upscaled pixels.

## Denoise

Settings → 画面 → **降噪**, default **off**. When on, `Denoise` runs a 5×5 median on the display luminance and a 3×3 median on the AGC-normalized temperature used for false color. Display-only: `kelvin16` / `MeasurementModel` still read the unfiltered native grid.

## Measurement

- Auto **max / min** markers on the live image.
- Always-on **center** point (toggle in settings).
- User points: tap add, drag move, long-press or dock delete, max 8.

## Versioning + GitHub Releases

- `versionName` starts at **1.0.0**, `versionCode` at **1** (`app/build.gradle.kts`).
- After each **subsequent** meaningful change: bump patch (`1.0.x` +1) and `versionCode` +1, update this file, commit, **push `origin/main`**, then publish a GitHub Release **with the signed APK**.
- Do **not** open pull requests.
- First Release is **1.0.0** (denoise + in-app updater included).
- Tag the release as `1.0.x` (no `v` prefix). Attach `TINY1-B-1.0.x.apk`.
- applicationId is always `com.pipidu.tiny1b` (no `.debug` suffix) so updates overwrite.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleRelease
gh release create 1.0.x app/build/outputs/apk/release/app-release.apk#TINY1-B-1.0.x.apk \
  --title "1.0.x" --notes "..." --target main
```

## Signing

Project key (committed so agents/CI produce updatable APKs):

| Field | Value |
|-------|--------|
| File | `keystore/tiny1b-release.jks` |
| Alias | `tiny1b` |
| Store / key password | `tiny1b` |
| applicationId | `com.pipidu.tiny1b` |

This is a **project update key**, not a high-security production secret. Debug and release both use `signingConfigs.project`.

## In-app update

Settings → 更新 → **检查更新**. Compares `BuildConfig.VERSION_NAME` to the latest GitHub Release tag. If newer, user downloads the APK to cache and installs via `FileProvider` + `REQUEST_INSTALL_PACKAGES` (unknown-sources permission on API 26+). Progress, errors, and “已是最新版本” are handled. No force-update.

## Layout

```
AGENTS.md
README.md
.gitignore
settings.gradle.kts / build.gradle.kts / gradle/
core/src/main/kotlin/com/pipidu/tiny1b/core/
app/src/main/java/com/pipidu/tiny1b/          product (ui, device, update)
app/src/main/java/com/zz/infisense/camera/    JNI names required by .so
app/src/main/jniLibs/arm64-v8a/               Tiny1-B UVC JNI
keystore/tiny1b-release.jks
```

## Build / run

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # JDK 21
./gradlew :core:test
./gradlew :app:assembleRelease
```

Install the **release** APK on an **arm64** phone with USB-OTG + Tiny1-B. Emulators cannot load the JNI `.so` files.

Optional: 设置 → 样例画面, to exercise palettes/ISR/points without hardware.

## Hardware-only gaps

- Real Tiny1-B USB attach, permission, UVC stream, shutter click, and KB cal cannot be verified in this environment.
- Only `arm64-v8a` vendor JNI is available.
- In-app install of a downloaded APK needs a physical device + unknown-sources permission.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
- After 1.0.0: bump version, push, publish a Release with the APK.
