package com.pipidu.tiny1b

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pipidu.tiny1b.ui.ThermalViewModel
import com.pipidu.tiny1b.ui.Tiny1BRoot
import com.pipidu.tiny1b.ui.theme.Tiny1BTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ThermalViewModel by viewModels {
        val app = application as Tiny1BApp
        ThermalViewModel.factory(app.container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paper = AndroidColor.parseColor("#F4F6FA")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(paper, paper),
        )
        setContent {
            Tiny1BTheme {
                Tiny1BRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
        handleUsbIntent(intent)
    }

    override fun onPause() {
        viewModel.onHostPaused()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.stop()
        }
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.retry()
        }
    }
}
