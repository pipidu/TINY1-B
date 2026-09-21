package com.pipidu.tiny1b

import android.content.Context
import com.pipidu.tiny1b.data.AppSettings
import com.pipidu.tiny1b.device.ThermalEngine

class AppContainer(context: Context) {
    val settings = AppSettings(context.applicationContext)
    val engine = ThermalEngine(context.applicationContext, settings)
}
