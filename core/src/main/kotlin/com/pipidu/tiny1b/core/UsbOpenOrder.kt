package com.pipidu.tiny1b.core

/**
 * Which [android.hardware.usb.UsbDevice] to pass to
 * [android.hardware.usb.UsbManager.openDevice].
 *
 * On hardware (1.0.7), the USB grant from [android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED]
 * lives on the Intent [android.hardware.usb.UsbManager.EXTRA_DEVICE]. The
 * matching [android.hardware.usb.UsbManager.getDeviceList] copy can still
 * report hasPermission=false and make openDevice return null. Open the extra
 * first when attachGrant; always try deviceList as a fallback even when the
 * two objects share the same deviceName.
 */
object UsbOpenOrder {
    enum class Source { INTENT_EXTRA, DEVICE_LIST }

    fun sources(hasExtra: Boolean, hasLive: Boolean, attachGrant: Boolean): List<Source> {
        val out = ArrayList<Source>(2)
        if (attachGrant && hasExtra) out += Source.INTENT_EXTRA
        if (hasLive) out += Source.DEVICE_LIST
        if (!attachGrant && hasExtra && Source.INTENT_EXTRA !in out) {
            out += Source.INTENT_EXTRA
        }
        return out
    }
}
