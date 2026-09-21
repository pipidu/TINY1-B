package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsbLiveDeviceTest {
    @Test
    fun prefersMatchingDeviceName() {
        val index = UsbLiveDevice.resolveIndex(
            listNames = listOf("/dev/bus/usb/001/003", "/dev/bus/usb/001/012"),
            listIds = intArrayOf(3, 12),
            preferredName = "/dev/bus/usb/001/012",
            preferredId = 99,
        )
        assertEquals(1, index)
    }

    @Test
    fun fallsBackToDeviceIdThenFirst() {
        assertEquals(
            1,
            UsbLiveDevice.resolveIndex(
                listNames = listOf("/dev/bus/usb/001/003", "/dev/bus/usb/001/012"),
                listIds = intArrayOf(3, 12),
                preferredName = "/dev/bus/usb/002/001",
                preferredId = 12,
            ),
        )
        assertEquals(
            0,
            UsbLiveDevice.resolveIndex(
                listNames = listOf("/dev/bus/usb/001/003"),
                listIds = intArrayOf(3),
                preferredName = null,
                preferredId = 0,
            ),
        )
        assertEquals(
            -1,
            UsbLiveDevice.resolveIndex(
                listNames = emptyList(),
                listIds = intArrayOf(),
                preferredName = "/dev/bus/usb/001/012",
                preferredId = 12,
            ),
        )
    }
}
