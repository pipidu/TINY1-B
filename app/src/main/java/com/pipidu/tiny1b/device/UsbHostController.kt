package com.pipidu.tiny1b.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.pipidu.tiny1b.core.Tiny1BFormat
import com.zz.infisense.camera.UVCCamera

class UsbHostController(context: Context) : UVCCamera.UsbHost {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var device: UsbDevice? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile var lastPermissionDenied: Boolean = false
        private set

    var onPermissionResult: ((granted: Boolean) -> Unit)? = null
    var onAttach: (() -> Unit)? = null
    var onDetach: (() -> Unit)? = null

    private val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
    private val attachFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            lastPermissionDenied = !granted
            onPermissionResult?.invoke(granted)
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttach?.invoke()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (gone != null && gone.vendorId == Tiny1BFormat.VENDOR_ID && gone.productId == Tiny1BFormat.PRODUCT_ID) {
                        close()
                        onDetach?.invoke()
                    }
                }
            }
        }
    }

    fun register() {
        registerInternal(permissionReceiver, permissionFilter)
        registerInternal(attachReceiver, attachFilter)
    }

    fun unregister() {
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(attachReceiver) }
    }

    fun findTiny1B(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { isTiny1B(it) }
    }

    fun isTiny1B(usbDevice: UsbDevice): Boolean {
        return usbDevice.vendorId == Tiny1BFormat.VENDOR_ID && usbDevice.productId == Tiny1BFormat.PRODUCT_ID
    }

    fun hasPermission(usbDevice: UsbDevice): Boolean = usbManager.hasPermission(usbDevice)

    fun requestPermission(usbDevice: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(usbDevice, pi)
    }

    fun open(usbDevice: UsbDevice): Boolean {
        if (!usbManager.hasPermission(usbDevice)) return false
        val opened = usbManager.openDevice(usbDevice) ?: return false
        device = usbDevice
        connection = opened
        lastPermissionDenied = false
        return true
    }

    fun close() {
        connection?.close()
        connection = null
        device = null
    }

    fun commands(): Tiny1BCommands? {
        val conn = connection ?: return null
        return Tiny1BCommands(conn)
    }

    override fun getVendorId(): Int = device?.vendorId ?: 0
    override fun getProductId(): Int = device?.productId ?: 0
    override fun getFileDescriptor(): Int = connection?.fileDescriptor ?: 0
    override fun getBusNum(): Int = parsePathIndex(getDeviceName(), 2)
    override fun getDevNum(): Int = parsePathIndex(getDeviceName(), 1)
    override fun getDeviceName(): String = device?.deviceName ?: ""
    override fun isOpen(): Boolean = connection != null

    private fun parsePathIndex(name: String, fromEnd: Int): Int {
        val parts = name.split("/")
        if (parts.size < fromEnd) return 0
        return parts[parts.size - fromEnd].toIntOrNull() ?: 0
    }

    private fun registerInternal(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

        fun hasUvcControl(usbDevice: UsbDevice): Boolean {
            for (i in 0 until usbDevice.interfaceCount) {
                val intf = usbDevice.getInterface(i)
                if (intf.interfaceClass == 14 && intf.interfaceSubclass == 1) {
                    for (e in 0 until intf.endpointCount) {
                        if (intf.getEndpoint(e).type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            return true
                        }
                    }
                }
            }
            return false
        }
    }
}
