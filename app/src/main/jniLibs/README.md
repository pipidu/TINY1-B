# jniLibs

Vendor UVC `.so` files (`libUVCCamera`, `libuvc`, `libusb`, `libjpeg-turbo`) were removed in 1.0.5.

Do **not** add them back. Capture is Kotlin/Java (`UvcCapture`) plus `app/src/main/cpp/usbfs_ioctl.c`.
