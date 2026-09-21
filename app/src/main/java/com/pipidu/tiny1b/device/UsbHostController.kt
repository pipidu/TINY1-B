package com.pipidu.tiny1b.device

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.pipidu.tiny1b.core.UsbLiveDevice
import com.pipidu.tiny1b.core.UsbOpenOrder
import com.pipidu.tiny1b.core.UsbPermissionSequence
import java.lang.ref.WeakReference

/**
 * USB host session for Tiny1-B.
 *
 * [UsbManager.ACTION_USB_DEVICE_ATTACHED] only means the module is present.
 * On ColorOS / targetSdk 35 it does **not** grant [UsbManager.hasPermission].
 * Never call [UsbManager.openDevice] unless hasPermission is true.
 *
 * [requestPermission] walks [UsbPermissionSequence] PendingIntent variants
 * so the system USB dialog can appear on Android 14/15. The permission
 * receiver stays registered on the application context (not unregistered
 * in onPause).
 */
class UsbHostController(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var device: UsbDevice? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile var lastPermissionDenied: Boolean = false
        private set
    @Volatile var lastPermissionRequestAt: Long = 0L
        private set
    @Volatile var lastPermissionKind: String = ""
        private set

    var onPermissionResult: ((granted: Boolean, hasGrantExtra: Boolean) -> Unit)? = null
    var onAttach: (() -> Unit)? = null
    var onDetach: (() -> Unit)? = null

    private var activityRef = WeakReference<Activity>(null)

    private val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
    private val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)

    @Volatile private var detachRegistered = false
    @Volatile private var permissionRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val hasExtra = intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission result granted=$granted hasExtra=$hasExtra kind=$lastPermissionKind")
            onPermissionResult?.invoke(granted, hasExtra)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val gone = intent.usbDeviceExtra()
            if (gone != null && isTiny1B(gone)) {
                onDetach?.invoke()
            }
        }
    }

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        ensurePermissionReceiver()
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef.get() === activity) {
            activityRef = WeakReference(null)
        }
        // Do not unregister the permission receiver on pause / unbind.
    }

    fun register() {
        if (!detachRegistered) {
            registerExported(appContext, detachReceiver, detachFilter)
            detachRegistered = true
        }
        ensurePermissionReceiver()
    }

    fun unregister() {
        if (detachRegistered) {
            runCatching { appContext.unregisterReceiver(detachReceiver) }
            detachRegistered = false
        }
        if (permissionRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            permissionRegistered = false
        }
    }

    fun findTiny1B(): UsbDevice? = liveTiny1B(null)

    fun liveTiny1B(preferred: UsbDevice? = null): UsbDevice? {
        val list = usbManager.deviceList.values.filter { isTiny1B(it) }
        if (list.isEmpty()) return null
        val index = UsbLiveDevice.resolveIndex(
            list.map { it.deviceName },
            list.map { it.deviceId }.toIntArray(),
            preferred?.deviceName,
            preferred?.deviceId ?: 0,
        )
        return list.getOrNull(index) ?: list.first()
    }

    fun isTiny1B(usbDevice: UsbDevice): Boolean {
        return usbDevice.vendorId == Tiny1BFormat.VENDOR_ID && usbDevice.productId == Tiny1BFormat.PRODUCT_ID
    }

    fun hasPermission(usbDevice: UsbDevice?): Boolean {
        if (usbDevice == null) return false
        return runCatching { usbManager.hasPermission(usbDevice) }.getOrDefault(false)
    }

    fun anyHasPermission(preferred: UsbDevice?): Boolean {
        return hasPermission(preferred) || hasPermission(liveTiny1B(preferred))
    }

    fun markDenied() {
        lastPermissionDenied = true
    }

    fun clearDenied() {
        lastPermissionDenied = false
    }

    fun connection(): UsbDeviceConnection? = connection

    fun openedDevice(): UsbDevice? = device

    fun isOpen(): Boolean = connection != null

    fun describe(usbDevice: UsbDevice): String {
        val perm = runCatching { usbManager.hasPermission(usbDevice).toString() }
            .getOrElse { "err:${it.javaClass.simpleName}" }
        return "name=${usbDevice.deviceName} id=${usbDevice.deviceId} " +
            "vid=${usbDevice.vendorId.toString(16)} pid=${usbDevice.productId.toString(16)} " +
            "ifaces=${usbDevice.interfaceCount} hasPermission=$perm"
    }

    fun tiny1bFromAttachIntent(intent: Intent?): UsbDevice? {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val extra = intent.usbDeviceExtra() ?: return null
        return extra.takeIf { isTiny1B(it) }
    }

    /**
     * Ask the system to show the USB permission dialog.
     * @return null if requestPermission was invoked, otherwise a Chinese error.
     */
    fun requestPermission(
        usbDevice: UsbDevice,
        kind: UsbPermissionSequence.PendingIntentKind,
        stepIndex: Int,
    ): String? {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing) {
            return "请将应用保持在前台后再插入模组。"
        }
        ensurePermissionReceiver()
        lastPermissionRequestAt = SystemClock.elapsedRealtime()
        lastPermissionKind = kind.name
        return try {
            val pi = pendingIntent(activity, kind, stepIndex)
            usbManager.requestPermission(usbDevice, pi)
            Log.i(
                TAG,
                "requestPermission kind=$kind step=$stepIndex ${describe(usbDevice)}",
            )
            null
        } catch (error: Throwable) {
            Log.w(TAG, "requestPermission kind=$kind", error)
            "requestPermission ${kind.name} 失败：${error.javaClass.simpleName}: ${error.message}"
        }
    }

    /**
     * Open Tiny1-B. Calls [UsbManager.openDevice] only on instances where
     * [UsbManager.hasPermission] is true.
     */
    @Synchronized
    fun open(preferred: UsbDevice?): OpenResult {
        val extra = preferred?.takeIf { isTiny1B(it) }
        val live = liveTiny1B(preferred)
        val extraPerm = hasPermission(extra)
        val livePerm = hasPermission(live)
        val order = UsbOpenOrder.sources(
            extraHasPermission = extraPerm,
            liveHasPermission = livePerm,
        )
        if (order.isEmpty()) {
            return OpenResult(
                ok = false,
                needsPermission = true,
                message = "没有 USB 权限（extra=${extra != null} extraPerm=$extraPerm " +
                    "live=${live != null} livePerm=$livePerm）。",
            )
        }
        val errors = ArrayList<String>()
        for (source in order) {
            val candidate = when (source) {
                UsbOpenOrder.Source.INTENT_EXTRA -> extra
                UsbOpenOrder.Source.DEVICE_LIST -> live
            } ?: continue
            val label = when (source) {
                UsbOpenOrder.Source.INTENT_EXTRA -> "intentExtra"
                UsbOpenOrder.Source.DEVICE_LIST -> "deviceList"
            }
            val result = tryOpen(candidate, label)
            if (result.ok) return result
            errors += result.message ?: label
        }
        return OpenResult(ok = false, message = errors.joinToString("；"))
    }

    private fun tryOpen(usbDevice: UsbDevice, source: String): OpenResult {
        val info = "$source ${describe(usbDevice)}"
        Log.i(TAG, "open try $info")
        if (!hasPermission(usbDevice)) {
            return OpenResult(
                ok = false,
                needsPermission = true,
                message = "没有 USB 权限（$info）。",
            )
        }
        if (connection != null && (device === usbDevice || device?.deviceName == usbDevice.deviceName)) {
            return OpenResult(ok = true, device = device, message = "already open fd=${fileDescriptor()}")
        }
        closeConnectionOnly()
        val opened = try {
            usbManager.openDevice(usbDevice)
        } catch (error: Throwable) {
            Log.e(TAG, "openDevice", error)
            return OpenResult(
                ok = false,
                message = "openDevice 异常 ${error.javaClass.simpleName}: ${error.message}（$info）。",
            )
        }
        if (opened == null) {
            return OpenResult(
                ok = false,
                message = "USB 已授权，但 UsbManager.openDevice 仍返回空（$info）。请关闭其它 USB 应用后重新插拔。",
            )
        }
        device = usbDevice
        connection = opened
        val fd = fileDescriptor()
        val cfg = applyConfiguration(opened, usbDevice)
        lastPermissionDenied = false
        Log.i(TAG, "openDevice ok fd=$fd setConfiguration=$cfg $info")
        return OpenResult(
            ok = true,
            device = usbDevice,
            message = "openDevice ok source=$source fd=$fd setConfiguration=$cfg",
        )
    }

    private fun applyConfiguration(opened: UsbDeviceConnection, usbDevice: UsbDevice): String {
        return try {
            if (usbDevice.configurationCount <= 0) return "none"
            val configuration = usbDevice.getConfiguration(0)
            val ok = opened.setConfiguration(configuration)
            if (ok) "ok id=${configuration.id}" else "false id=${configuration.id}"
        } catch (error: Throwable) {
            Log.w(TAG, "setConfiguration", error)
            "err:${error.javaClass.simpleName}:${error.message}"
        }
    }

    @Synchronized
    fun close() {
        closeConnectionOnly()
        device = null
    }

    @Synchronized
    private fun closeConnectionOnly() {
        runCatching { connection?.close() }
        connection = null
    }

    fun commands(): Tiny1BCommands? {
        val conn = connection ?: return null
        return Tiny1BCommands(conn)
    }

    private fun fileDescriptor(): Int {
        val conn = connection ?: return 0
        return try {
            conn.fileDescriptor
        } catch (error: Throwable) {
            Log.e(TAG, "fileDescriptor", error)
            0
        }
    }

    @android.annotation.SuppressLint("UnspecifiedImmutableFlag")
    private fun pendingIntent(
        activity: Activity,
        kind: UsbPermissionSequence.PendingIntentKind,
        stepIndex: Int,
    ): PendingIntent {
        val requestCode = 200 + stepIndex
        return when (kind) {
            UsbPermissionSequence.PendingIntentKind.PACKAGE_MUTABLE -> {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(activity.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                PendingIntent.getBroadcast(activity, requestCode, intent, flags)
            }
            UsbPermissionSequence.PendingIntentKind.IMPLICIT_UNSAFE -> {
                val intent = Intent(ACTION_USB_PERMISSION)
                var flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                }
                PendingIntent.getBroadcast(activity, requestCode, intent, flags)
            }
            UsbPermissionSequence.PendingIntentKind.DEMO_FLAGS_0 -> {
                val intent = Intent(ACTION_USB_PERMISSION)
                PendingIntent.getBroadcast(activity, requestCode, intent, 0)
            }
        }
    }

    private fun ensurePermissionReceiver() {
        if (permissionRegistered) return
        registerExported(appContext, permissionReceiver, permissionFilter)
        permissionRegistered = true
    }

    private fun registerExported(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    data class OpenResult(
        val ok: Boolean,
        val device: UsbDevice? = null,
        val message: String? = null,
        val needsPermission: Boolean = false,
    )

    companion object {
        const val ACTION_USB_PERMISSION = "com.pipidu.tiny1b.USB_PERMISSION"
        private const val TAG = "UsbHostController"
    }
}
