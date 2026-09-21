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
    fun onlyPermittedInstancesAreOpened() {
        assertEquals(
            listOf(UsbOpenOrder.Source.INTENT_EXTRA, UsbOpenOrder.Source.DEVICE_LIST),
            UsbOpenOrder.sources(extraHasPermission = true, liveHasPermission = true),
        )
        assertEquals(
            listOf(UsbOpenOrder.Source.DEVICE_LIST),
            UsbOpenOrder.sources(extraHasPermission = false, liveHasPermission = true),
        )
        assertEquals(
            listOf(UsbOpenOrder.Source.INTENT_EXTRA),
            UsbOpenOrder.sources(extraHasPermission = true, liveHasPermission = false),
        )
        assertEquals(
            emptyList<UsbOpenOrder.Source>(),
            UsbOpenOrder.sources(extraHasPermission = false, liveHasPermission = false),
        )
    }
}

class UsbPermissionSequenceTest {
    @Test
    fun walksPendingIntentKindsThenBothDeviceObjects() {
        val steps = UsbPermissionSequence.steps(hasExtra = true, hasLive = true)
        assertEquals(6, steps.size)
        assertEquals(UsbPermissionSequence.PendingIntentKind.PACKAGE_MUTABLE, steps[0].kind)
        assertEquals(UsbPermissionSequence.Target.INTENT_EXTRA, steps[0].target)
        assertEquals(UsbPermissionSequence.Target.DEVICE_LIST, steps[1].target)
        assertEquals(UsbPermissionSequence.PendingIntentKind.IMPLICIT_UNSAFE, steps[2].kind)
        assertEquals(UsbPermissionSequence.PendingIntentKind.DEMO_FLAGS_0, steps[4].kind)
    }

    @Test
    fun liveOnlyHasThreeVariants() {
        val steps = UsbPermissionSequence.steps(hasExtra = false, hasLive = true)
        assertEquals(3, steps.size)
        assertEquals(
            listOf(
                UsbPermissionSequence.PendingIntentKind.PACKAGE_MUTABLE,
                UsbPermissionSequence.PendingIntentKind.IMPLICIT_UNSAFE,
                UsbPermissionSequence.PendingIntentKind.DEMO_FLAGS_0,
            ),
            steps.map { it.kind },
        )
        assertEquals(true, steps.all { it.target == UsbPermissionSequence.Target.DEVICE_LIST })
    }

    @Test
    fun emptyWhenNoDevice() {
        assertEquals(
            emptyList<UsbPermissionSequence.Step>(),
            UsbPermissionSequence.steps(hasExtra = false, hasLive = false),
        )
    }
}
