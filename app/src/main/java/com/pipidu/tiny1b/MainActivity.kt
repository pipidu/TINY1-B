package com.pipidu.tiny1b

import android.content.Intent
import android.graphics.Color as AndroidColor
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
        viewModel.onLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        viewModel.bindUsb(this)
        window.decorView.post {
            if (!isFinishing) {
                viewModel.onHostResumed()
            }
        }
    }

    override fun onPause() {
        viewModel.onHostPaused()
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.unbindUsb(this)
        if (isFinishing) {
            viewModel.stop()
        }
        super.onDestroy()
    }
}
