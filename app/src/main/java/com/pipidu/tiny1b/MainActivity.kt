package com.pipidu.tiny1b

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        setContent {
            Tiny1BTheme {
                Tiny1BRoot(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
        viewModel.onHostResumed()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }
}
