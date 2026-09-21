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
import java.lang.ref.WeakReference

/**
 * USB host session for Tiny1-B.
 *
 * Grant path is the system USB attach dialog: Activity
 * [UsbManager.ACTION_USB_DEVICE_ATTACHED] + `device_filter.xml`. That intent
 * already carries permission on its [UsbManager.EXTRA_DEVICE] — [open] that
 * object first, without [requestPermission] and without requiring
 * [UsbManager.hasPermission] on the deviceList copy.
 *
 * [requestPermission] is a one-shot fallback for cold start, or when the
 * attach extra failed to open and the deviceList copy still has no permission.
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

    var onPermissionResult: ((granted: Boolean, hasGrantExtra: Boolean) -> Unit)? = null
    var onAttach: (() -> Unit)? = null
    var onDetach: (() -> Unit)? = null

    private var activityRef = WeakReference<Activity>(null)

    private val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

    @Volatile private var detachRegistered = false
    @Volatile private var permissionRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val hasExtra = intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission result granted=$granted hasExtra=$hasExtra")
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
        ensurePermissionReceiver(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef.get() === activity) {
            activityRef = WeakReference(null)
        }
        if (permissionRegistered) {
            runCatching { activity.unregisterReceiver(permissionReceiver) }
            permissionRegistered = false
        }
    }

    fun register() {
        if (!detachRegistered) {
            registerLegacy(appContext, detachReceiver, detachFilter)
            detachRegistered = true
        }
    }

    fun unregister() {
        if (detachRegistered) {
            runCatching { appContext.unregisterReceiver(detachReceiver) }
            detachRegistered = false
        }
    }

    fun findTiny1B(): UsbDevice? = liveTiny1B(null)

    /**
     * Tiny1-B from [UsbManager.deviceList], matching [preferred] by name then
     * id. Fallback identity only — attach grant opens the Intent extra first.
     */
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

    fun hasPermission(usbDevice: UsbDevice): Boolean = usbManager.hasPermission(usbDevice)

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

    /**
     * Tiny1-B from a system [UsbManager.ACTION_USB_DEVICE_ATTACHED] Activity
     * intent. That EXTRA_DEVICE is the granted object — open it first.
     */
    fun tiny1bFromAttachIntent(intent: Intent?): UsbDevice? {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val extra = intent.usbDeviceExtra() ?: return null
        return extra.takeIf { isTiny1B(it) }
    }

    /**
     * One-shot cold-start fallback. Not used when the Activity was started
     * with [UsbManager.ACTION_USB_DEVICE_ATTACHED].
     */
    fun requestPermission(usbDevice: UsbDevice): String? {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing) {
            return "请将应用保持在前台后再插入模组。"
        }
        return try {
            ensurePermissionReceiver(activity)
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(activity.packageName)
            }
            val pi = PendingIntent.getBroadcast(activity, 0, intent, permissionPiFlags())
            lastPermissionRequestAt = SystemClock.elapsedRealtime()
            usbManager.requestPermission(usbDevice, pi)
            Log.i(TAG, "requestPermission (one-shot) vid=${usbDevice.vendorId} pid=${usbDevice.productId}")
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "retry USB PI with FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT", error)
            try {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(activity.packageName)
                }
                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                val pi = PendingIntent.getBroadcast(activity, 0, intent, flags)
                lastPermissionRequestAt = SystemClock.elapsedRealtime()
                usbManager.requestPermission(usbDevice, pi)
                null
            } catch (retry: Throwable) {
                Log.e(TAG, "requestPermission", retry)
                "无法弹出 USB 授权。请拔掉 Tiny1-B 再插入，在系统窗口选择「允许」。"
            }
        } catch (error: Throwable) {
            Log.e(TAG, "requestPermission", error)
            "无法弹出 USB 授权。请拔掉 Tiny1-B 再插入，在系统窗口选择「允许」。"
        }
    }

    /**
     * @param preferred attach-intent extra (the granted object) or any Tiny1-B hint.
     * @param grantedByAttachIntent true when the Activity intent was
     *   [UsbManager.ACTION_USB_DEVICE_ATTACHED] — open [preferred] first and
     *   do not require [UsbManager.hasPermission] on the deviceList copy.
     */
    @Synchronized
    fun open(preferred: UsbDevice?, grantedByAttachIntent: Boolean = false): OpenResult {
        var last: OpenResult? = null
        var needsListPermission = false
        repeat(OPEN_ATTEMPTS) { attempt ->
            val extra = preferred?.takeIf { isTiny1B(it) }
            val live = liveTiny1B(preferred)
            val order = UsbOpenOrder.sources(
                hasExtra = extra != null,
                hasLive = live != null,
                attachGrant = grantedByAttachIntent,
            )
            if (order.isEmpty()) {
                last = OpenResult(
                    ok = false,
                    message = "未找到 Tiny1-B（VID 0BDA / PID 3901），attempt=${attempt + 1}。",
                )
                if (attempt < OPEN_ATTEMPTS - 1) SystemClock.sleep(OPEN_RETRY_MS)
                return@repeat
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
                val result = tryOpen(candidate, grantedByAttachIntent, label)
                if (result.ok) return result
                if (source == UsbOpenOrder.Source.DEVICE_LIST &&
                    !runCatching { usbManager.hasPermission(candidate) }.getOrDefault(false)
                ) {
                    needsListPermission = true
                }
                errors += result.message ?: label
            }
            last = OpenResult(
                ok = false,
                message = errors.joinToString("；"),
                needsListPermission = needsListPermission,
            )
            if (attempt < OPEN_ATTEMPTS - 1) SystemClock.sleep(OPEN_RETRY_MS)
        }
        return last ?: OpenResult(ok = false, message = "无法打开 Tiny1-B。")
    }

    private fun tryOpen(
        usbDevice: UsbDevice,
        grantedByAttachIntent: Boolean,
        source: String,
    ): OpenResult {
        val info = "$source ${describe(usbDevice)} attachGrant=$grantedByAttachIntent"
        Log.i(TAG, "open try $info")
        val hasPerm = runCatching { usbManager.hasPermission(usbDevice) }.getOrDefault(false)
        if (!grantedByAttachIntent && !hasPerm) {
            return OpenResult(
                ok = false,
                needsListPermission = source == "deviceList",
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
            val hint = when (source) {
                "intentExtra" -> "这是系统插拔授权绑定的设备对象。请关闭其它相机/USB 应用后再试。"
                "deviceList" -> "系统插拔授权在 Intent EXTRA_DEVICE 上；deviceList 副本 hasPermission 经常仍为 false。"
                else -> "请关闭其它相机/USB 应用后再试。"
            }
            return OpenResult(
                ok = false,
                needsListPermission = source == "deviceList" && !hasPerm,
                message = "UsbManager.openDevice 返回空（$info）。$hint",
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

    private fun ensurePermissionReceiver(activity: Activity) {
        if (permissionRegistered) return
        registerLegacy(activity, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        permissionRegistered = true
    }

    private fun registerLegacy(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun permissionPiFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
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
        val needsListPermission: Boolean = false,
    )

    companion object {
        const val ACTION_USB_PERMISSION = "com.pipidu.tiny1b.USB_PERMISSION"
        private const val TAG = "UsbHostController"
        private const val OPEN_ATTEMPTS = 3
        private const val OPEN_RETRY_MS = 80L
    }
}
