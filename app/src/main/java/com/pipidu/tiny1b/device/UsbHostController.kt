package com.pipidu.tiny1b.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.zz.infisense.camera.UVCCamera

class UsbHostController(context: Context) : UVCCamera.UsbHost {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var device: UsbDevice? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile var lastPermissionDenied: Boolean = false
        private set

    var onAttach: (() -> Unit)? = null
    var onDetach: (() -> Unit)? = null

    private val attachFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    @Volatile private var attachRegistered = false

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

    fun register() {
        if (!attachRegistered) {
            // USB attach/detach are system broadcasts and must be exported.
            registerInternal(attachReceiver, attachFilter, exported = true)
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
     * Ask for USB permission. The PendingIntent targets [UsbPermissionReceiver]
     * explicitly (component + action, no extras) so UsbManager can fill in
     * EXTRA_PERMISSION_GRANTED. Returns a Chinese error if the request itself failed.
     */
    fun requestPermission(usbDevice: UsbDevice): String? {
        return try {
            val intent = Intent(appContext, UsbPermissionReceiver::class.java).apply {
                action = ACTION_USB_PERMISSION
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
            usbManager.requestPermission(usbDevice, pi)
            Log.i(TAG, "requestPermission issued for vid=${usbDevice.vendorId} pid=${usbDevice.productId}")
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

    /**
     * Close the Java USB connection only after native UVC has released the fd.
     */
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

    private fun registerInternal(receiver: BroadcastReceiver, filter: IntentFilter, exported: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val flags = if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
            appContext.registerReceiver(receiver, filter, flags)
        } else {
            appContext.registerReceiver(receiver, filter)
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
        private const val REQUEST_CODE = 0x71B1
        private const val TAG = "UsbHostController"
    }
}
