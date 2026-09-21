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
import java.lang.ref.WeakReference

/**
 * USB host session for Tiny1-B.
 *
 * Permission (targetSdk 35): ACTION + setPackage(applicationId), FLAG_MUTABLE,
 * RECEIVER_EXPORTED. Request only while resumed. Keep the permission receiver
 * across onPause (the system USB dialog pauses the Activity). Do not use
 * setComponent. 被拒 is decided by [ThermalEngine], not by an instant deny.
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

    var onPermissionResult: ((granted: Boolean) -> Unit)? = null
    var onAttach: (() -> Unit)? = null
    var onDetach: (() -> Unit)? = null

    private var activityRef = WeakReference<Activity>(null)

    private val attachFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    @Volatile private var attachRegistered = false
    @Volatile private var permissionRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission result granted=$granted hasExtra=${intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)}")
            onPermissionResult?.invoke(granted)
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val attached = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (attached == null || isTiny1B(attached)) {
                        onAttach?.invoke()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (gone != null && isTiny1B(gone)) {
                        onDetach?.invoke()
                    }
                }
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
        if (!attachRegistered) {
            registerLegacy(appContext, attachReceiver, attachFilter)
            attachRegistered = true
        }
    }

    fun unregister() {
        if (attachRegistered) {
            runCatching { appContext.unregisterReceiver(attachReceiver) }
            attachRegistered = false
        }
    }

    fun findTiny1B(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { isTiny1B(it) }
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

    fun isOpen(): Boolean = connection != null && fileDescriptor() > 0

    /**
     * Current Android USB permission: action + setPackage (not setComponent),
     * FLAG_MUTABLE, receiver exported so UsbManager can deliver the result.
     */
    fun requestPermission(usbDevice: UsbDevice): String? {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing) {
            return "请将应用保持在前台后再授权 USB。"
        }
        return try {
            ensurePermissionReceiver(activity)
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(activity.packageName)
            }
            val pi = PendingIntent.getBroadcast(activity, 0, intent, permissionPiFlags())
            lastPermissionRequestAt = SystemClock.elapsedRealtime()
            usbManager.requestPermission(usbDevice, pi)
            Log.i(TAG, "requestPermission issued vid=${usbDevice.vendorId} pid=${usbDevice.productId}")
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
                "无法弹出 USB 授权（${retry.javaClass.simpleName}）。请拔掉后重新插入模组。"
            }
        } catch (error: Throwable) {
            Log.e(TAG, "requestPermission", error)
            "无法弹出 USB 授权（${error.javaClass.simpleName}）。请拔掉后重新插入模组。"
        }
    }

    @Synchronized
    fun open(usbDevice: UsbDevice): Boolean {
        return try {
            if (!usbManager.hasPermission(usbDevice)) return false
            if (connection != null && device?.deviceId == usbDevice.deviceId) {
                return fileDescriptor() > 0
            }
            closeConnectionOnly()
            val opened = usbManager.openDevice(usbDevice) ?: return false
            device = usbDevice
            connection = opened
            lastPermissionDenied = false
            if (fileDescriptor() <= 0) {
                closeConnectionOnly()
                device = null
                return false
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "open", error)
            closeConnectionOnly()
            device = null
            false
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

    fun describe(usbDevice: UsbDevice): String = buildString {
        append("vid=${usbDevice.vendorId.toString(16)} pid=${usbDevice.productId.toString(16)}")
        append(" ifaces=${usbDevice.interfaceCount}")
        for (i in 0 until usbDevice.interfaceCount) {
            val iface = usbDevice.getInterface(i)
            append(" [id=${iface.id} alt=${iface.alternateSetting} class=${iface.interfaceClass}/${iface.interfaceSubclass}")
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                append(" ep=${Integer.toHexString(ep.address)} t=${ep.type} max=${ep.maxPacketSize}")
            }
            append("]")
        }
    }

    private fun fileDescriptor(): Int {
        val conn = connection ?: return 0
        return try {
            val fd = conn.fileDescriptor
            if (fd > 0) fd else 0
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

    /**
     * Permission result is delivered by UsbManager (system). The receiver must be
     * exported. Attach/detach are also system broadcasts.
     */
    private fun registerLegacy(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * targetSdk 35: FLAG_MUTABLE so UsbManager can fill EXTRA_PERMISSION_GRANTED.
     * Intent uses setPackage (package-explicit), not setComponent.
     */
    private fun permissionPiFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.pipidu.tiny1b.USB_PERMISSION"
        private const val TAG = "UsbHostController"
    }
}
