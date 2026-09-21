package com.pipidu.tiny1b.core

/**
 * Pick which Tiny1-B in [UsbManager.deviceList] corresponds to an attach-intent extra.
 *
 * Android [android.hardware.usb.UsbManager.openDevice] often returns null for the
 * parcelled [android.hardware.usb.UsbManager.EXTRA_DEVICE] even when
 * [android.hardware.usb.UsbManager.hasPermission] is true. Always open the
 * live list instance (same as the Infiray demo `UsbControlBlock.getUsbDevice`).
 */
object UsbLiveDevice {
    /**
     * @return index into the parallel [listNames] / [listIds] arrays, or -1 if empty.
     */
    fun resolveIndex(
        listNames: List<String>,
        listIds: IntArray,
        preferredName: String?,
        preferredId: Int,
    ): Int {
        if (listNames.isEmpty()) return -1
        if (!preferredName.isNullOrEmpty()) {
            val byName = listNames.indexOf(preferredName)
            if (byName >= 0) return byName
        }
        if (preferredId != 0) {
            for (i in listIds.indices) {
                if (listIds[i] == preferredId) return i
            }
        }
        return 0
    }
}
