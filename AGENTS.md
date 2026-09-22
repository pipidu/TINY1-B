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

On `main`: **1.0.16** (`versionCode` **17**). Compose Chinese light UI. USB via demo **libUVCCamera** + `UsbControlBlock.requestPermission` (`PendingIntent` **flags=0**, action `com.zz.infisense.camera.USB_PERMISSION.`). **targetSdk 26**. Activity `USB_DEVICE_ATTACHED` + `device_filter.xml` (VID `0x0BDA` / PID `0x3901`) so the system offers this app on insert; streaming still uses the demo JNI open path. Unplug: waiting-connect UI first, then `abandon()`. Frames: demo split of 256×384 YUYV into **192×256** image + **192×256** Kelvin-16. Display rotation 0/90/180/270 persisted. Fast bilinear ISR. Software **帧生成** (OFF / 2× / 3×, default off). Temperature legend is a strip **below** the live image (not over pixels). Live dock is **快门 / 拍照 / 录像 / 测温**. 快门 and 测温 require a live Tiny1-B (grayed on sample / disconnected). Photo / record hints overlay the live image (no dock layout shift). Min/max markers dwell **400 ms** (or jump immediately at ≥ **1.0 °C**). In-app GitHub updater for `pipidu/TINY1-B` (download is single-flight; `cacheDir/updates` keeps **one** APK). Live bitmaps / UVC / ISR / frame-gen history are capped and recycled.

## Current architecture

```
core/     Pure JVM: frame split, temperature, palettes, software ISR, denoise, measurement, version parse
app/      Android: demo UVC JNI + Compose UI (Chinese) + GitHub updater
app/src/main/java/com/zz/infisense/camera/   vendor UVCCamera wrappers (required by .so)
app/src/main/jniLibs/arm64-v8a/              vendor .so (arm64 only)
keystore/ Project signing key (required so later APKs overwrite the same install)
```

- `ThermalEngine` constructs `UVCCamera(0x0BDA, 0x3901, 256, 384, activity, handler)`, `create()`, then `open()` with the demo 5s retry. `onFrame` → triple UVC scratch → `FrameParser.parseUvcFrame`. Unplug: set Searching + `bitmap=null` first, then `UVCCamera.abandon()` (no native stop/release/destroy). Do not leave a frozen last frame with status 已连接.
- `LiveViewScreen` is a Column: top chrome (title / fps / status / 设置), thermal stage, **below-image** legend strip + dock **快门 / 拍照 / 录像 / 测温**. 色板 / ISR / 帧生成 / 旋转 live in Settings. 快门 and 测温 are disabled unless `DeviceStatus.Live`. Empty/permission/error cards when not live.
- `SettingsScreen` covers 色板, ISR, **帧生成**, 画面旋转, 降噪, min/max, center, **手动快门**, shutter max **120s**, KB cal, sample preview, **清除缓存**, **检查更新**.
- `AppUpdater` queries `https://api.github.com/repos/pipidu/TINY1-B/releases/latest` (user-initiated). `OneShotGate` + immediate `Downloading` so double-tap cannot start two downloads. Prunes `cacheDir/updates` to the APK being downloaded.

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

1. USB host matches **VID `0x0BDA` / PID `0x3901`**, preview size **256×384** YUYV. Do **not** change this JNI/USB path.
2. `UsbControlBlock.getUsbCamera`: walk `UsbManager.getDeviceList()` for VID/PID, require UVC control interface (class 14 / subclass 1) + interrupt endpoint, then **`hasPermission`**. If false: `requestPermission` with **flags=0** and return false. **Never** invent Kotlin `openDevice` / attach-extra / deviceList grant logic from 1.0.5–1.0.9.
3. `UVCCamera.open()`: `getUsbCamera` then `nativeConnect(mNativePtr, vid, pid, fd, bus, dev, usbfs)` then `nativeSetPreviewSize`. `getFileDescriptor()` is the only `openDevice` call, and only after `hasPermission` is true (demo layout).
4. On grant, handler `USB_PERMISSION` / `USB_PERMIT` (or the 5s `attachRetryRunnable`) calls `open()` again now that permission exists, then `setFrameCallback` + `startPreview`.
5. Activity `USB_DEVICE_ATTACHED` + `res/xml/device_filter.xml` (VID `0x0BDA` / PID `0x3901`, decimal 3034 / 14593) so Android **offers this app** when the camera is inserted. `singleTask` + `onNewIntent` brings the existing Activity; then the **demo JNI** `tryOpenCamera` / `requestPermission` flags=0 path runs. Do **not** open the attach `EXTRA_DEVICE` with Kotlin `UsbManager` (1.0.6–1.0.9). Also keep a dynamic `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` receiver on the **application** context for the engine lifetime — do **not** unregister it in `onPause` (OEM pause-on-unplug misses DETACHED and freezes 已连接). DETACHED is Tiny1-B only.
6. `targetSdk` **26** like the demo. Do **not** ship targetSdk 33–35 USB PendingIntent variants (`setPackage` + `FLAG_MUTABLE`, etc.). Those never showed a working dialog on this phone.
7. compileSdk 35 needs `registerReceiver(..., RECEIVER_EXPORTED)` on API 33+. That is the only USB API addition; permission PI stays flags=0.
8. Do **not** destroy the JNI camera in `onPause` (USB dialog pauses the Activity). On **unplug**, update Compose/engine state **first** (`DeviceStatus.Searching`, `bitmap=null`, chip 未连接), then `UVCCamera.abandon()`: drop the Java frame callback and close `UsbDeviceConnection`, but **skip** `nativeStopPreview` / `nativeRelease` / `nativeDestroy` (those SIGSEGV after the device is gone). Recreate a new `UVCCamera` on the next open. While Live, if `deviceList` no longer has Tiny1-B, take the same detach path (do not early-return `tryOpenCamera` as still previewing). Call `destroy()` only when the Activity is finishing with USB still present. Resume posts the same 5s open retry as the demo.
9. Each JNI frame is **196608** bytes. Demo `onFrame`: first half → YUYV **192×256** (`imageWidth = 384/2`, `imageHeight = 256`), second half → Kelvin-16 **192×256**. `FrameParser` matches that split and does **not** reshape as 256×192 then rotate (1.0.10: four horizontal bands + left/right stripes). Y is even YUYV bytes. Palette / ISR / denoise / measurement use this native temperature grid. `°C = raw/16 − 273.15`.
10. Shutter / KB cal / shutter-max stay on `UsbControlBlock` control transfers: manual shutter `0x0345`, KB cal `0x0341`, shutter max get `0x038A` / set `0x03C4` (`bmRequestType` `0x41` / `0xC1`).

Do **not** add back: `UsbHostController`, `UvcCapture`, `Usbfs`, `Tiny1BCommands`, `tiny1busb` NDK, `core/.../uvc/*`, `UsbLiveDevice`, `UsbOpenOrder`, `UsbPermissionSequence`.

## Connect survivability

1. `MainActivity` is `singleTask` so a second launcher instance is not created.
2. `UVCCamera` is constructed with the **Activity** context (demo). Application context is not enough for `requestPermission`.
3. While resumed, `open()` is retried every **5s** until it returns true (demo `attachRetryRunnable`).
4. Failures set `DeviceStatus.Error` / permission cards with a Chinese message. **重新扫描** posts an immediate retry.
5. Instant `granted=false` from the demo receiver is treated as 被拒 (demo). User taps 重新扫描 to ask again.
6. Unplug: `USB_DEVICE_DETACHED` (Tiny1-B VID/PID only) or a 1s `deviceList` poll while Live. Always `Searching` + `bitmap=null` **before** `abandon()`. Chip shows 未连接. Never keep a frozen last frame.

## Frame generation (帧生成)

Vendor demo has **no** interpolate / synthesize UI — native UVC fps only. Product setting (default **关闭**):

| Chip | Extra display frames per native interval |
|------|------------------------------------------|
| 关闭 | 0 (show each native plane immediately) |
| 2× 插帧 | 1 (blend at 1/2, then the new native plane) |
| 3× 插帧 | 2 (blend at 1/3, 2/3, then the new native plane) |

One-frame delay: extra frames are synthesized from the **last two** native `ThermalPlanes` (Y + Kelvin-16 lerp) after the newer frame arrives. History is **two native holds + one blend destination**, never a growing list. If the native interval is already **< 35 ms**, interpolation is skipped so a fast module is not charged extra ISP work. Measurement samples the **blended** Kelvin grid so markers move with the extra frames. Display fps includes interpolated frames.

## Cache (1.0.15)

User report: software cache / RAM too large. Findings and caps:

| Source | Before | Cap / recycle |
|--------|--------|----------------|
| Live `Bitmap.createBitmap` every frame, never recycled (ISR 4× 768×1024 ARGB ~3 MB × ~23 fps until GC) | Unbounded | **3** slot ring; `setPixels` on a slot that is not the currently displayed Compose bitmap |
| JNI `onFrame` `copyOf(196608)` every callback | Unbounded short-lived copies | **3** UVC scratch buffers (skip the slot the worker is reading) |
| ISR / palette `FloatArray` + `IntArray` every frame | Unbounded | One `IspScratch` reused (native + scaled + ARGB) |
| `FrameParser` luminance/Kelvin every frame | Unbounded | Reused parse buffers + **2** oriented plane holds |
| 帧生成 history | Would have grown if stored as a list | **2** native planes + **1** blend dest |
| Sample preview `SyntheticScene.uvcFrame()` new `ByteArray` every tick | Unbounded | **2** sample UVC buffers; sample posts into the same worker |
| `cacheDir/updates/*.apk` | Every downloaded version kept | Keep **1** APK (plus `.part` during download) |
| Video encoder | `MediaCodec` input **surface**; `offer()` draws the live bitmap — no extra encoder bitmap queue | Unchanged (already bounded) |
| Photo | One ARGB copy, recycled after JPEG | Unchanged |
| Measurement user points | Already max **8** | Unchanged |

Settings → 存储 → **清除缓存** deletes `cacheDir` / `externalCacheDir` except an in-progress update APK. It does **not** stop USB preview, recycle the on-screen live bitmap, delete gallery photos/videos, or wipe SharedPreferences. While Live, idle frame-gen `prev` is dropped; working UVC/ISR/bitmap slots stay.

## ISR

`SuperResolution`: percentile-AGC on temperature, fuse Y-detail at **native** resolution, then **bilinear** 2×/4× (not 4×4 bicubic — that tanked fps on 1.0.11). Palette LUT after upsample. **Measurement always samples the oriented native grid**, not the upscaled pixels.

## Display orientation

Settings → 画面 → **画面旋转** (0° / 90° / 180° / 270°). Stored in `tiny1b_settings`. Custom measurement points remap with the rotation. Horizontal mirror still applies after rotation. There is **no** live-dock 旋转 button.

## UI chrome

Column: top card (title / fps / status / 设置) → **live image (fully visible)** → bottom chrome. The temperature legend (min · palette bar · max) is a **strip below the image**, never overlaid on pixels and never in the top card. Dock in that same bottom chrome: **快门 / 拍照 / 录像 / 测温**. Photo / record `captureHint` is a **floating overlay** on the image (snackbar), not a row inside the dock — the image, legend, and dock must not shift. 色板 / ISR / 帧生成 / 旋转 belong in Settings. Do not put a vertical bar on the right of the live image. 快门 and 测温 are grayed unless a Tiny1-B is Live; 拍照 / 录像 stay enabled for sample preview as well as Live.

## Capture

Dock **快门 / 拍照 / 录像 / 测温** (with the legend strip in the same bottom chrome). JPEG and H.264 MP4 (no mic) go to the system album (`Pictures/TINY1-B`, `Movies/TINY1-B`) via MediaStore on API 29+; API 26–28 uses `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion 28`) and a media scan. Unplug stops an in-progress recording. Sample preview can also capture; it cannot run 快门 or 测温. Capture feedback (`captureHint`: saved photo, record timer, errors) overlays the live image and must not grow the bottom chrome.

## Denoise

Settings → 画面 → **降噪**, default **off**. When on, `Denoise` runs a 5×5 median on the display luminance and a 3×3 median on the AGC-normalized temperature used for false color. Display-only: `kelvin16` / `MeasurementModel` still read the unfiltered native grid.

## Measurement

- Auto **max / min** markers on the live image, with dwell / hysteresis so they do not flicker between nearby pixels every frame:
  - Hold radius **4 px** on the native grid (same thermal blob).
  - A farther candidate must keep winning for **400 ms** (`MeasurementModel.EXTREMA_DWELL_MS`) before the marker moves.
  - Immediate move if the new extrema is at least **1.0 °C** more extreme than the locked pixel (`EXTREMA_JUMP_KELVIN16 = 16`).
  - The color-bar min/max numbers still follow the true frame extrema. Center and custom points are not delayed.
- Always-on **center** point (toggle in settings).
- User points: tap add, drag move, long-press or dock delete, max 8. **Only while 测温 is active** — a tap on the image with 测温 off must not add a point.

## Versioning + GitHub Releases

- Current: **1.0.16** (`versionCode` **17**).
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
- **1.0.10**: delete the from-scratch Kotlin UVC/USBFS product. Ship the vendor demo USB+JNI path (`libUVCCamera`, `com.zz.infisense.camera`, targetSdk 26, flags=0) and port ISR / denoise / measurement / palettes / settings / GitHub updater onto it. Hardware: connects ~23 fps, but image was four stacked bands + side stripes.
- **1.0.11**: frame decode matches demo `onFrame` (192×256 YUYV + Kelvin-16, no 256×192 rotate). USB/JNI unchanged.
- **1.0.12**: persisted 0/90/180/270 display rotation; bilinear ISR so 超分 does not crush fps; temperature legend moved into the top FPS card.
- **1.0.13**: unplug returns to waiting-connect (no native destroy SIGSEGV); 测温 must be on to add points; 拍照/录像; shutter max 120s; update download is single-flight; live image sits below the top card.
- **1.0.14**: live dock only 拍照/录像/测温; 快门/色板/ISR/旋转 in Settings; legend strip **below** the image. Unplug clears the frame and sets 未连接 (DETACHED + device-list poll; do not freeze 已连接). Activity `USB_DEVICE_ATTACHED` + `device_filter.xml` restores the system “open with this app” chooser; connect is still demo JNI.
- **1.0.15**: Settings **帧生成** (software 2×/3× temporal blend; demo has none; default off). Dock **快门 / 拍照 / 录像 / 测温**; 快门 and 测温 grayed without a live Tiny1-B. Cap/recycle live bitmaps (3), UVC scratch (3), ISR scratch, frame-gen history (2+1), sample UVC (2), update APKs (1). Settings **清除缓存**. USB/JNI path unchanged.
- **1.0.16**: min/max markers dwell 400 ms (4 px hold, 1.0 °C immediate jump) so they stop flickering; photo/record hints overlay the live image and no longer shift the dock. USB/JNI unchanged.

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

Settings → 更新 → **检查更新**. Compares `BuildConfig.VERSION_NAME` to the latest GitHub Release **tag** (`AppVersion`, so 1.0.3 is newer than 1.0.2). Parser must tolerate logins like `cursor[bot]` (brackets inside JSON strings). HTTP uses User-Agent `TINY1-B/<version> (+https://github.com/pipidu/TINY1-B)`, follows GitHub → `release-assets.githubusercontent.com` redirects **without** the API `Accept` header, and stores the APK under `cacheDir/updates/` (**one** APK; older files pruned). Install uses `FileProvider` + `ClipData` + `REQUEST_INSTALL_PACKAGES` (unknown-sources screen on API 26+). Failures show a Chinese error; no force-update.

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
