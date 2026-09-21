# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

The product **is** the Infiray Android demo USB/JNI camera path, with this repo’s Compose UI and imaging pipeline on top. That is allowed. Do **not** reintroduce the 1.0.5–1.0.9 Kotlin `UsbManager` / UVC / USBFS stack — it never opened Tiny1-B on ColorOS.

## Vendor zip vs product JNI (mandatory)

- The vendor zip is a **private reference**: `https://doges3.img.shygo.cn/zfiledownload/Other/热成像.zip`. Download outside the repo.
- **Do not commit** the zip, extracted `热成像/` tree, demo `MainActivity` / layouts / ISP (`PseudocolorProcessor`, `TemperatureSampler`, …), `libir_sample`, Windows DLLs, or unused sample APKs (`ir_demo.apk`).
- **Do commit** the working camera stack taken from that demo:
  - `app/src/main/jniLibs/arm64-v8a/{libUVCCamera,libuvc,libusb100,libjpeg-turbo1500}.so`
  - `app/src/main/java/com/zz/infisense/camera/{UVCCamera,UsbControlBlock,IFrameCallback,Size}.java`
- JNI native methods and `mNativePtr` are bound to Java package **`com.zz.infisense.camera`**. Do not rename that package, `UVCCamera` field layout (`protected long mNativePtr`), or the four `System.loadLibrary` names/order.
- `applicationId` is **`com.pipidu.tiny1b`** (JNI does not depend on it). Keep the project JKS so updates overwrite 1.0.0–1.0.9.

## Current status

On `main`: **1.0.10** (`versionCode` **11**). Compose Chinese light UI. USB via demo **libUVCCamera** + `UsbControlBlock.requestPermission` (`PendingIntent` **flags=0**, action `com.zz.infisense.camera.USB_PERMISSION.`). **targetSdk 26** (same as the demo that actually opens Tiny1-B). Frames go to `FrameParser` → optional denoise → software ISR → palettes → measurement. In-app GitHub updater for `pipidu/TINY1-B`.

## Current architecture

```
core/     Pure JVM: frame split, temperature, palettes, software ISR, denoise, measurement, version parse
app/      Android: demo UVC JNI + Compose UI (Chinese) + GitHub updater
app/src/main/java/com/zz/infisense/camera/   vendor UVCCamera wrappers (required by .so)
app/src/main/jniLibs/arm64-v8a/              vendor .so (arm64 only)
keystore/ Project signing key (required so later APKs overwrite the same install)
```

- `ThermalEngine` constructs `UVCCamera(0x0BDA, 0x3901, 256, 384, activity, handler)`, `create()`, then `open()` with the demo 5s retry. `onFrame` → `FrameParser.parseUvcFrame`.
- `LiveViewScreen` is connect-or-live: empty/permission/error cards, thermal stage, palettes, measurement dock.
- `SettingsScreen` covers ISR, 降噪, min/max, center, shutter, KB cal, sample preview, **检查更新**.
- `AppUpdater` queries `https://api.github.com/repos/pipidu/TINY1-B/releases/latest` (user-initiated).

## UI theme

Primary look is **light** (`Theme.kt` `lightColorScheme`):

| Token | Hex | Use |
|-------|-----|-----|
| Paper | `#F4F6FA` | page / window / system bars |
| Surface | `#FFFFFF` | cards, top chrome, bottom dock, updater sections |
| Ink | `#1C2430` | primary text / icons |
| Muted | `#5B6778` | secondary text |
| Accent | `#2563EB` | buttons, active chips, switches |
| Hot / Cold / Live | `#E11D48` / `#0284C7` / `#059669` | status + image markers |

Thermal **palettes stay on the image** (`Palettes` LUT). Measurement labels on the stage use a dark chip so they stay readable on any LUT. Settings, live view, empty/permission/error cards, and updater dialogs all use the same light surfaces.

## Tiny1-B integration (demo JNI — keep this path)

1. USB host matches **VID `0x0BDA` / PID `0x3901`**, preview size **256×384** YUYV (stacked 256×192 image + 256×192 Kelvin-16).
2. `UsbControlBlock.getUsbCamera`: walk `UsbManager.getDeviceList()` for VID/PID, require UVC control interface (class 14 / subclass 1) + interrupt endpoint, then **`hasPermission`**. If false: `requestPermission` with **flags=0** and return false. **Never** invent Kotlin `openDevice` / attach-extra / deviceList grant logic from 1.0.5–1.0.9.
3. `UVCCamera.open()`: `getUsbCamera` then `nativeConnect(mNativePtr, vid, pid, fd, bus, dev, usbfs)` then `nativeSetPreviewSize`. `getFileDescriptor()` is the only `openDevice` call, and only after `hasPermission` is true (demo layout).
4. On grant, handler `USB_PERMISSION` / `USB_PERMIT` (or the 5s `attachRetryRunnable`) calls `open()` again now that permission exists, then `setFrameCallback` + `startPreview`.
5. Dynamic `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` receivers (demo). **No** Activity `USB_DEVICE_ATTACHED` + `device_filter.xml` — that path did not grant on ColorOS.
6. `targetSdk` **26** like the demo. Do **not** ship targetSdk 33–35 USB PendingIntent variants (`setPackage` + `FLAG_MUTABLE`, etc.). Those never showed a working dialog on this phone.
7. compileSdk 35 needs `registerReceiver(..., RECEIVER_EXPORTED)` on API 33+. That is the only USB API addition; permission PI stays flags=0.
8. Do **not** destroy the JNI camera in `onPause` (USB dialog pauses the Activity). Destroy on detach and when the Activity is finishing. Resume posts the same 5s open retry as the demo.
9. Each JNI frame is **196608** bytes. Split + rotate 90° CCW to **192×256** portrait (`FrameParser`). `°C = raw/16 − 273.15`.
10. Shutter / KB cal / shutter-max stay on `UsbControlBlock` control transfers: manual shutter `0x0345`, KB cal `0x0341`, shutter max get `0x038A` / set `0x03C4` (`bmRequestType` `0x41` / `0xC1`).

Do **not** add back: `UsbHostController`, `UvcCapture`, `Usbfs`, `Tiny1BCommands`, `tiny1busb` NDK, `core/.../uvc/*`, `UsbLiveDevice`, `UsbOpenOrder`, `UsbPermissionSequence`.

## Connect survivability

1. `MainActivity` is `singleTask` so a second launcher instance is not created.
2. `UVCCamera` is constructed with the **Activity** context (demo). Application context is not enough for `requestPermission`.
3. While resumed, `open()` is retried every **5s** until it returns true (demo `attachRetryRunnable`).
4. Failures set `DeviceStatus.Error` / permission cards with a Chinese message. **重新扫描** posts an immediate retry.
5. Instant `granted=false` from the demo receiver is treated as 被拒 (demo). User taps 重新扫描 to ask again.

## ISR

`SuperResolution`: percentile-AGC on temperature, Catmull-Rom 2× (optional second pass for 4×), unsharp Y-detail fused into the temperature field, then palette LUT. **Measurement always samples the native 192×256 temperature grid**, not the upscaled pixels.

## Denoise

Settings → 画面 → **降噪**, default **off**. When on, `Denoise` runs a 5×5 median on the display luminance and a 3×3 median on the AGC-normalized temperature used for false color. Display-only: `kelvin16` / `MeasurementModel` still read the unfiltered native grid.

## Measurement

- Auto **max / min** markers on the live image.
- Always-on **center** point (toggle in settings).
- User points: tap add, drag move, long-press or dock delete, max 8.

## Versioning + GitHub Releases

- Current: **1.0.10** (`versionCode` **11**).
- `versionName` started at **1.0.0**, `versionCode` at **1** (`app/build.gradle.kts`).
- After each **subsequent** meaningful change: bump patch (`1.0.x` +1) and `versionCode` +1, update this file, commit, **push `origin/main`**, then publish a GitHub Release **with the signed APK**.
- Do **not** open pull requests.
- Tag the release as `1.0.x` (no `v` prefix). Attach `TINY1-B-1.0.x.apk`.
- applicationId is always `com.pipidu.tiny1b` (no `.debug` suffix) so updates overwrite.
- **1.0.1**: crash-survivable Tiny1-B USB connect + light UI as the primary look.
- **1.0.2**: attempted USB-denied fix + in-app GitHub updater (permission dialog still broken on hardware).
- **1.0.3**: USB grant path matches Infiray demo (`targetSdk 26`) — OS “old Android” warning; JNI `mNativePtr` missing.
- **1.0.4**: targetSdk 35 USB (`setPackage` + `FLAG_MUTABLE` + exported receiver) + `mNativePtr` JNI field.
- **1.0.5**: drop vendor `.so` / JNI wrappers; Kotlin UVC. In-app `requestPermission` still did not show a system dialog on hardware (授权 USB loop).
- **1.0.6**: USB grant via Activity `USB_DEVICE_ATTACHED` + `device_filter.xml`; open on attach intent without `requestPermission`. Hardware: grant worked, `openDevice` on the parcelled extra still showed 无法打开设备.
- **1.0.7**: open the live `deviceList` Tiny1-B after grant; `setConfiguration`; USBDEVFS disconnect + force-claim unique VC/VS; real USB errors on the connect card. Hardware: grant was on Intent extra; list `hasPermission=false` and `openDevice(list)` returned null.
- **1.0.8**: attach grant opens Intent `EXTRA_DEVICE` first (even when deviceName matches the list copy); do not require list `hasPermission`; fallback list + one `requestPermission` on that instance. Hardware: extra **and** list `hasPermission=false`, both `openDevice` null; attachGrant was a lie.
- **1.0.9**: never `openDevice` unless `hasPermission`; show 正在请求 USB 权限 and walk targetSdk 35 `requestPermission` PI variants (setPackage+MUTABLE, implicit+UNSAFE, demo flags=0). Instant false is not 被拒. Open the instance that reports true. Hardware: still could not connect.
- **1.0.10**: delete the from-scratch Kotlin UVC/USBFS product. Ship the vendor demo USB+JNI path (`libUVCCamera`, `com.zz.infisense.camera`, targetSdk 26, flags=0) and port ISR / denoise / measurement / palettes / settings / GitHub updater onto it.

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

If a future change were forced to keep demo `applicationId` `com.dashazi.p2demo` for JNI (it is **not** required — JNI is the Java package), the user must uninstall the old app; do not do that unless the `.so` stops loading.

## In-app update

Settings → 更新 → **检查更新**. Compares `BuildConfig.VERSION_NAME` to the latest GitHub Release **tag** (`AppVersion`, so 1.0.3 is newer than 1.0.2). Parser must tolerate logins like `cursor[bot]` (brackets inside JSON strings). HTTP uses User-Agent `TINY1-B/<version> (+https://github.com/pipidu/TINY1-B)`, follows GitHub → `release-assets.githubusercontent.com` redirects **without** the API `Accept` header, and stores the APK under `cacheDir/updates/`. Install uses `FileProvider` + `ClipData` + `REQUEST_INSTALL_PACKAGES` (unknown-sources screen on API 26+). Failures show a Chinese error; no force-update.

## Layout

```
AGENTS.md
README.md
.gitignore
settings.gradle.kts / build.gradle.kts / gradle/
core/src/main/kotlin/com/pipidu/tiny1b/core/          imaging pipeline (no UVC protocol)
app/src/main/java/com/pipidu/tiny1b/                  product (ui, engine, update)
app/src/main/java/com/zz/infisense/camera/            demo UVCCamera / UsbControlBlock
app/src/main/jniLibs/arm64-v8a/*.so                   demo libUVCCamera stack
keystore/tiny1b-release.jks
```

## Build / run

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # JDK 21; NDK not required
./gradlew :core:test
./gradlew :app:assembleRelease
```

Install the **release** APK on a phone with USB-OTG + Tiny1-B (arm64). Sample preview in settings does not need hardware. The OS may warn that the app targets an older Android (targetSdk 26) — that is required for the demo USB dialog.

## Hardware-only gaps

- Real Tiny1-B USB attach, permission dialog, UVC stream, shutter, and KB cal cannot be verified in this environment.
- In-app install of a downloaded APK needs a physical device + unknown-sources permission.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
- After 1.0.0: bump version, push, publish a Release with the APK.
