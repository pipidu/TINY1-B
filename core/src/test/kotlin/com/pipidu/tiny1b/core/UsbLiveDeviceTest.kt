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

class UsbOpenOrderTest {
    @Test
    fun attachGrantOpensIntentExtraBeforeDeviceList() {
        assertEquals(
            listOf(UsbOpenOrder.Source.INTENT_EXTRA, UsbOpenOrder.Source.DEVICE_LIST),
            UsbOpenOrder.sources(hasExtra = true, hasLive = true, attachGrant = true),
        )
    }

    @Test
    fun attachGrantWithOnlyExtra() {
        assertEquals(
            listOf(UsbOpenOrder.Source.INTENT_EXTRA),
            UsbOpenOrder.sources(hasExtra = true, hasLive = false, attachGrant = true),
        )
    }

    @Test
    fun attachGrantWithOnlyLiveStillTriesList() {
        assertEquals(
            listOf(UsbOpenOrder.Source.DEVICE_LIST),
            UsbOpenOrder.sources(hasExtra = false, hasLive = true, attachGrant = true),
        )
    }

    @Test
    fun withoutAttachGrantPrefersDeviceListThenExtra() {
        assertEquals(
            listOf(UsbOpenOrder.Source.DEVICE_LIST, UsbOpenOrder.Source.INTENT_EXTRA),
            UsbOpenOrder.sources(hasExtra = true, hasLive = true, attachGrant = false),
        )
        assertEquals(
            listOf(UsbOpenOrder.Source.DEVICE_LIST),
            UsbOpenOrder.sources(hasExtra = false, hasLive = true, attachGrant = false),
        )
    }

    @Test
    fun emptyWhenNeitherExists() {
        assertEquals(
            emptyList<UsbOpenOrder.Source>(),
            UsbOpenOrder.sources(hasExtra = false, hasLive = false, attachGrant = true),
        )
    }
}
