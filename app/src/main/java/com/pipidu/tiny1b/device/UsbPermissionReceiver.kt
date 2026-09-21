package com.pipidu.tiny1b.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.pipidu.tiny1b.Tiny1BApp

/**
 * Manifest-registered, explicit-component receiver for [UsbManager.requestPermission].
 * Dynamically registered NOT_EXPORTED receivers plus package-only Intents drop or
 * mis-deliver the system fill-in extras on Android 14+.
 */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbHostController.ACTION_USB_PERMISSION) return
        if (!intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)) {
            Log.w(TAG, "ignoring USB permission broadcast without grant extra")
            return
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val app = context.applicationContext as? Tiny1BApp
        if (app == null) {
            Log.e(TAG, "application is not Tiny1BApp")
            return
        }
        app.container.engine.onUsbPermissionResult(granted)
    }

    companion object {
        private const val TAG = "UsbPermissionReceiver"
    }
}
