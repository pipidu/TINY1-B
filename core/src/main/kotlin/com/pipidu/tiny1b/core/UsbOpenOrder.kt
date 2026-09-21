package com.pipidu.tiny1b.core

/**
 * Which UsbDevice to pass to UsbManager.openDevice: only instances with
 * hasPermission==true. Attach intent is not a grant.
 */
object UsbOpenOrder {
    enum class Source { INTENT_EXTRA, DEVICE_LIST }

    fun sources(extraHasPermission: Boolean, liveHasPermission: Boolean): List<Source> {
        val out = ArrayList<Source>(2)
        if (extraHasPermission) out += Source.INTENT_EXTRA
        if (liveHasPermission) out += Source.DEVICE_LIST
        return out
    }
}

/**
 * requestPermission PendingIntent variants for targetSdk 35 / Android 14–15 / ColorOS.
 * Walk in order until hasPermission becomes true or the user refuses.
 * Instant granted=false without a pause is not a refusal — try the next step.
 */
object UsbPermissionSequence {
    enum class Target { INTENT_EXTRA, DEVICE_LIST }

    enum class PendingIntentKind {
        /** Activity context, Intent.setPackage, FLAG_MUTABLE, RECEIVER_EXPORTED. */
        PACKAGE_MUTABLE,
        /** No setPackage, FLAG_MUTABLE | FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT (API 34+). */
        IMPLICIT_UNSAFE,
        /** Demo: flags=0 PendingIntent, no setPackage. */
        DEMO_FLAGS_0,
    }

    data class Step(val target: Target, val kind: PendingIntentKind)

    fun steps(hasExtra: Boolean, hasLive: Boolean): List<Step> {
        val targets = ArrayList<Target>(2)
        if (hasExtra) targets += Target.INTENT_EXTRA
        if (hasLive) targets += Target.DEVICE_LIST
        if (targets.isEmpty()) return emptyList()
        val out = ArrayList<Step>(targets.size * PendingIntentKind.values().size)
        for (kind in PendingIntentKind.values()) {
            for (target in targets) {
                out += Step(target, kind)
            }
        }
        return out
    }
}
