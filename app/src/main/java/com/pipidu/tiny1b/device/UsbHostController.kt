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
import com.zz.infisense.camera.UVCCamera
import java.lang.ref.WeakReference

/**
 * USB host session matching the Infiray demo's Tiny1-B grant path:
 * Activity-context implicit PendingIntent with flags=0, dynamic permission
 * receiver, openDevice only after hasPermission.
 */
class UsbHostController(context: Context) : UVCCamera.UsbHost {
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
            lastPermissionDenied = !granted
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

    /**
     * Same call sequence as the vendor demo [UsbControlBlock.requestPermission]:
     * implicit action-only Intent, PendingIntent flags 0, register receiver on the
     * Activity, then [UsbManager.requestPermission].
     */
    fun requestPermission(usbDevice: UsbDevice): String? {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing) {
            return "请将应用保持在前台后再授权 USB。"
        }
        return try {
            ensurePermissionReceiver(activity)
            val intent = Intent(ACTION_USB_PERMISSION)
            val pi = PendingIntent.getBroadcast(activity, 0, intent, permissionPiFlags())
            lastPermissionRequestAt = SystemClock.elapsedRealtime()
            usbManager.requestPermission(usbDevice, pi)
            Log.i(TAG, "requestPermission issued vid=${usbDevice.vendorId} pid=${usbDevice.productId} flags=${permissionPiFlags()}")
            null
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

    override fun getVendorId(): Int = device?.vendorId ?: 0
    override fun getProductId(): Int = device?.productId ?: 0
    override fun getFileDescriptor(): Int = fileDescriptor()
    override fun getBusNum(): Int = parsePathIndex(getDeviceName(), 2)
    override fun getDevNum(): Int = parsePathIndex(getDeviceName(), 1)
    override fun getDeviceName(): String = device?.deviceName.orEmpty()
    override fun isOpen(): Boolean = connection != null && fileDescriptor() > 0

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

    private fun parsePathIndex(name: String, fromEnd: Int): Int {
        val parts = name.split("/")
        if (parts.size < fromEnd) return 0
        return parts[parts.size - fromEnd].toIntOrNull() ?: 0
    }

    private fun ensurePermissionReceiver(activity: Activity) {
        if (permissionRegistered) return
        registerLegacy(activity, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        permissionRegistered = true
    }

    /**
     * Demo registers with the two-arg [Context.registerReceiver] (no exported flag).
     * targetSdk 26 keeps that legal on Android 13+; only use the 33+ overload if
     * we ever raise targetSdk again.
     */
    private fun registerLegacy(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        val target = context.applicationInfo.targetSdkVersion
        if (Build.VERSION.SDK_INT >= 33 && target >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * Demo: PendingIntent flags = 0. That is mutable on targetSdk < 31.
     * If targetSdk is ever raised to 34+, implicit USB PIs need
     * FLAG_MUTABLE | FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT — not an explicit component.
     */
    private fun permissionPiFlags(): Int {
        val target = appContext.applicationInfo.targetSdkVersion
        return when {
            Build.VERSION.SDK_INT >= 34 && target >= 34 -> {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            }
            Build.VERSION.SDK_INT >= 31 && target >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
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
