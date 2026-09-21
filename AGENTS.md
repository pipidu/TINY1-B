# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

## Vendor demo is reference-only (mandatory)

- The vendor zip is a **private reference**. Download and inspect it outside the repo (`https://doges3.img.shygo.cn/zfiledownload/Other/热成像.zip`).
- **Do not commit** the zip, extracted demo project, demo APKs, sample assets, `libir_sample`, Windows DLLs, or vendor sample app source (`MainActivity`, demo layouts, etc.).
- If any of that lands on `main`, delete it, tighten `.gitignore`, and commit the cleanup to `main`.
- **Do not** ship vendor native libraries: no `libUVCCamera`, `libuvc`, `libusb`, `libjpeg-turbo`, and no `com.zz.infisense.camera` JNI wrappers. Those were removed in **1.0.5**.

## Current status

On `main`: Compose app, light UI, **targetSdk 35**, USB grant via Activity `USB_DEVICE_ATTACHED` + `device_filter.xml` (VID `0x0BDA` / PID `0x3901`), **Kotlin/Java UVC**, ISR, denoise (default off), palettes, measurement, in-app GitHub update. No vendor demo tree and no vendor `.so` in git. **1.0.7** opens the live `UsbManager.deviceList` instance after grant (not the parcelled attach extra).

## Current architecture

```
core/     Pure JVM: UVC descriptor/probe/payload, frame split, temperature, palettes, software ISR, denoise, measurement, version parse
app/      Android: USB host + UVC capture, Compose UI (Chinese), GitHub updater
app/src/main/cpp/  tiny1busb — USBDEVFS ioctl helper (SUBMITURB/REAPURB/DISCONNECT/CLAIM). Not libuvc/libusb.
keystore/ Project signing key (required so later APKs overwrite the same install)
```

- `ThermalEngine` opens Tiny1-B, pulls YUYV frames, runs optional denoise + ISR, publishes `EngineState`.
- `LiveViewScreen` is connect-or-live: empty/permission/error cards, thermal stage, palettes, measurement dock.
- `SettingsScreen` covers ISR, 降噪, min/max, center, shutter, KB cal, sample preview, **检查更新**.
- `AppUpdater` queries `https://api.github.com/repos/pipidu/TINY1-B/releases/latest` (user-initiated).

USB grant matches the Infiray demo model: plugging Tiny1-B into a phone with this app installed shows Android’s system USB access / “open with” dialog (`USB_DEVICE_ATTACHED` on `MainActivity` + `@xml/device_filter`). That intent **already carries permission** — `openDevice` without `requestPermission`. The in-app **授权 USB** button was a dead loop on 1.0.5 and is gone.

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

## Tiny1-B integration (no vendor .so)

1. USB host matches **VID `0x0BDA` / PID `0x3901`**.
2. After permission, `UsbHostController.open` resolves Tiny1-B from `usbManager.deviceList` (name, then id, then VID/PID) and calls `UsbManager.openDevice` on **that** instance. The attach-intent `EXTRA_DEVICE` is only a grant hint — parcelled extras often make `openDevice` return null even when `hasPermission` is true (Infiray demo `UsbControlBlock.getUsbDevice` always uses the list).
3. Do **not** fail solely because `fileDescriptor() <= 0`; bulk streaming uses the connection object. `setConfiguration` is attempted after open.
4. `UvcCapture` detaches a kernel UVC driver (`USBDEVFS_IOCTL` `DISCONNECT`) then `claimInterface(iface, true)` on unique Video Control (class 14 / subclass 1) and Video Streaming (class 14 / subclass 2) interface **ids**. Android enumerates each altsetting as a separate `UsbInterface` — claim alt 0, then `setInterface` to the streaming alt.
5. `getRawDescriptors()` is parsed in `core` (`UvcDescriptors`) for uncompressed **YUY2 256×384**. Fallback walks `UsbDevice` interfaces if the blob is incomplete.
6. UVC `VS_PROBE` / `VS_COMMIT` via class interface control transfers (`bmRequestType` `0x21` / `0xA1`, `SET_CUR` `0x01`, `GET_CUR` `0x81`). Try GET_LEN size then 26/34/48. Failures show the `controlTransfer` return value.
7. Stream: **bulk IN** if the VS interface has one (`UsbDeviceConnection.bulkTransfer`); otherwise **isochronous IN** with USBFS multi-packet URBs (`tiny1busb` ioctl on the same connection fd). `UsbRequest` cannot do isoc on AOSP (libusbhost rejects it).
8. Payloads are framed with the UVC header (FID / EOF / ERR) in `UvcPayloadAssembler` into **196608-byte** frames.
9. Each frame is split: **256×192** image + **256×192** Kelvin-16 temperature, then rotated 90° CCW to **192×256** portrait (`FrameParser`).
10. Temperature: `°C = raw/16 − 273.15`.
11. Vendor control transfers (`Tiny1BCommands`) on the same `UsbDeviceConnection`: manual shutter `0x0345`, KB cal `0x0341`, shutter max get `0x038A` / set `0x03C4` (`bmRequestType` `0x41` / `0xC1`). No `.so` required.

## USB permission (targetSdk 35)

Grant path is **plug-in**, not an in-app button. Same idea as the vendor demo (the demo has no “授权 USB” card; the system attach dialog is the grant).

1. `MainActivity` is `singleTask` + `exported` and has `android.hardware.usb.action.USB_DEVICE_ATTACHED` with meta-data `@xml/device_filter` (`vendor-id` 3034 = `0x0BDA`, `product-id` 14593 = `0x3901`).
2. `onCreate` / `onNewIntent`: if the action is `USB_DEVICE_ATTACHED`, take `UsbManager.EXTRA_DEVICE` as a **grant flag** and identity hint, then open the matching **deviceList** instance. Do **not** call `requestPermission`. Do **not** pass the parcelled extra to `openDevice` when a list instance exists.
3. Cold start with the module already plugged: if `hasPermission`, open the list instance. If not, **one** `requestPermission` (ACTION + `setPackage` + `FLAG_MUTABLE`, receiver `RECEIVER_EXPORTED`). If no system dialog / no grant extra, show **请拔掉再插入** — never loop 需要 USB 权限 / 授权 USB.
4. Do **not** use `setComponent` (1.0.2: instant 被拒). Do **not** ship `targetSdk 26` (1.0.3: “built for an older Android”).
5. Do **not** stop the engine in `onPause` (the system USB dialog pauses the Activity). `stop()` only in `onDestroy` when `isFinishing`.
6. Detach still uses a dynamic `USB_DEVICE_DETACHED` receiver (`RECEIVER_EXPORTED`).
7. Connect failures must surface the real USB result (`openDevice` null/exception, claim errno, `controlTransfer` n) — not a generic 无法打开设备.

VID `0x0BDA` / PID `0x3901` unchanged.

## Connect survivability

1. **USB_DEVICE_ATTACHED second Activity** — `launchMode="singleTask"` + `onNewIntent` so a plug-in does not spawn another Activity.
2. Connect runs on `tiny1b-connect` under a mutex. Failures set `DeviceStatus.Error` with a Chinese `errorMessage` plus the underlying USB result (`openDevice` / claim / `controlTransfer`).
3. In-app `requestPermission` is a **one-shot fallback** only. 1.0.5 treated instant `granted=false` as “tap 授权 USB again”, which never granted on hardware.
4. After a system attach grant, open the **deviceList** Tiny1-B. 1.0.6 passed the parcelled extra into `openDevice` and showed 无法打开设备.

## ISR

`SuperResolution`: percentile-AGC on temperature, Catmull-Rom 2× (optional second pass for 4×), unsharp Y-detail fused into the temperature field, then palette LUT. **Measurement always samples the native 192×256 temperature grid**, not the upscaled pixels.

## Denoise

Settings → 画面 → **降噪**, default **off**. When on, `Denoise` runs a 5×5 median on the display luminance and a 3×3 median on the AGC-normalized temperature used for false color. Display-only: `kelvin16` / `MeasurementModel` still read the unfiltered native grid.

## Measurement

- Auto **max / min** markers on the live image.
- Always-on **center** point (toggle in settings).
- User points: tap add, drag move, long-press or dock delete, max 8.

## Versioning + GitHub Releases

- Current: **1.0.7** (`versionCode` **8**).
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
- **1.0.7**: open the live `deviceList` Tiny1-B after grant; `setConfiguration`; USBDEVFS disconnect + force-claim unique VC/VS; real USB errors on the connect card.

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

Settings → 更新 → **检查更新**. Compares `BuildConfig.VERSION_NAME` to the latest GitHub Release **tag** (`AppVersion`, so 1.0.3 is newer than 1.0.2). Parser must tolerate logins like `cursor[bot]` (brackets inside JSON strings). HTTP uses User-Agent `TINY1-B/<version> (+https://github.com/pipidu/TINY1-B)`, follows GitHub → `release-assets.githubusercontent.com` redirects **without** the API `Accept` header, and stores the APK under `cacheDir/updates/`. Install uses `FileProvider` + `ClipData` + `REQUEST_INSTALL_PACKAGES` (unknown-sources screen on API 26+). Failures show a Chinese error; no force-update.

## Layout

```
AGENTS.md
README.md
.gitignore
settings.gradle.kts / build.gradle.kts / gradle/
core/src/main/kotlin/com/pipidu/tiny1b/core/          imaging + UVC protocol
core/src/main/kotlin/com/pipidu/tiny1b/core/uvc/      descriptors, probe, payload assembler
app/src/main/java/com/pipidu/tiny1b/                  product (ui, device, update)
app/src/main/cpp/usbfs_ioctl.c                        USBFS isoc + DISCONNECT/CLAIM ioctl helper
keystore/tiny1b-release.jks
```

## Build / run

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # JDK 21, NDK 27.x for tiny1busb
./gradlew :core:test
./gradlew :app:assembleRelease
```

Install the **release** APK on a phone with USB-OTG + Tiny1-B. Sample preview in settings does not need hardware.

## Hardware-only gaps

- Real Tiny1-B USB attach, permission dialog, UVC stream, shutter, and KB cal cannot be verified in this environment.
- Isochronous needs the compiled `tiny1busb` helper (USBFS multi-packet URBs). Bulk path is pure Java.
- In-app install of a downloaded APK needs a physical device + unknown-sources permission.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
- After 1.0.0: bump version, push, publish a Release with the APK.
