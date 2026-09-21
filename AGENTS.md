# TINY1-B — Agent notes

This repository is being replaced with a production Android thermal imaging app for the Infiray Tiny1-B USB module.

## Current status

- Repo previously contained only a README title (`TINY1-B`).
- Vendor demo zip has been downloaded for study (not to be shipped as the product).
- Next: inspect Tiny1-B USB/SDK APIs, drop only the SDK binaries/headers, then scaffold a new Compose app on `main`.

## Product goals

- Infiray Tiny1-B thermal module over USB, using the vendor SDK (same integration path as the demo).
- Software ISR (image super-resolution) on the live thermal image.
- Live min/max temperature markers.
- Center measurement point plus user-custom points (add / move / remove), with live temperatures.
- Production UI in Chinese: connect, live view, palettes, measurement, settings, permissions, empty/error states.

## Build / run

Not scaffolded yet. Android Gradle project will live at repo root.

## Layout

```
AGENTS.md          this file
README.md          short product title (legacy, will be replaced)
```

## Process

- Work only on `main`. Push every change to `origin/main`.
- Do not open pull requests.
- Keep other branches deleted.
- Update this file after every meaningful change.
