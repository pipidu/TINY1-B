package com.pipidu.tiny1b.core

/**
 * Pick which Tiny1-B in UsbManager.deviceList corresponds to an attach-intent extra
 * (by deviceName, then deviceId). Attach is not a USB grant — openDevice only after
 * hasPermission is true on that instance.
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
