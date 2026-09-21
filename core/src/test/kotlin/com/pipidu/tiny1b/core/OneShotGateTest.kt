package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OneShotGateTest {
    @Test
    fun secondEnterFailsUntilExit() {
        val gate = OneShotGate()
        assertTrue(gate.tryEnter())
        assertTrue(gate.isBusy())
        assertFalse(gate.tryEnter())
        gate.exit()
        assertFalse(gate.isBusy())
        assertTrue(gate.tryEnter())
    }
}
