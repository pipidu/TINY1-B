# TINY1-B — Agent notes

Production Android app for the Infiray Tiny1-B USB thermal module.

## Vendor demo is reference-only (mandatory)

- The vendor zip is a **private reference**. Download and inspect it outside the repo.
- **Do not commit** the zip, extracted demo project, demo APKs, sample assets, `libir_sample`, Windows DLLs, or vendor sample app source (`MainActivity`, demo layouts, etc.).
- If any of that lands on `main`, delete it, tighten `.gitignore`, and commit the cleanup to `main`.
- The product may keep **only** the Tiny1-B pieces required to build: arm64 JNI `.so` files (`libUVCCamera`, `libuvc`, `libusb100`, `libjpeg-turbo1500`) plus the JNI Java class names those libraries bind to.
- JNI symbols are compiled as `com.zz.infisense.camera.UVCCamera` / `IFrameCallback`. Those two classes are SDK glue, not the demo UI.

## Current status

- `main` has agent notes and ignore rules. App scaffold is next.
- Device path (from private demo study, not shipped): UVC **VID `0x0BDA` / PID `0x3901`**, stream **256×384** YUYV, split into **256×192** image + **256×192** temperature, rotate 90° CCW for portrait. Temperature is `uint16` little-endian, **°C = raw/16 − 273.15**.

## Product goals

- Tiny1-B over USB using the vendor UVC JNI stack.
- Software ISR super-resolution on the live thermal image.
- Live min/max temperature markers.
- Center point plus user points (add / move / remove) with live °C.
- Chinese production UI: connect, live view, palettes, measurement, settings, USB permission, empty/error states.

## Layout (target)

```
AGENTS.md                 this file
README.md                 product readme (Chinese)
.gitignore
app/                      Android application (Compose)
core/                     pure-JVM imaging / measure / parse (unit-tested)
```

## Build / run

Not scaffolded yet. After scaffold:

```bash
./gradlew :core:test
./gradlew :app:assembleDebug
```

Install the debug APK on an **arm64** Android phone with USB-OTG; Tiny1-B is not an emulator device.

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
