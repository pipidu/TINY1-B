package com.pipidu.tiny1b.core

import java.util.concurrent.atomic.AtomicBoolean

/** Process-local mutex for “do this once until finished” UI actions. */
class OneShotGate {
    private val busy = AtomicBoolean(false)

    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun isBusy(): Boolean = busy.get()

    fun exit() {
        busy.set(false)
    }
}
