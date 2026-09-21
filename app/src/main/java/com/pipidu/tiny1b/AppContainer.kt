package com.pipidu.tiny1b

import android.content.Context
import com.pipidu.tiny1b.data.AppSettings
import com.pipidu.tiny1b.device.ThermalEngine
import com.pipidu.tiny1b.update.AppUpdater

class AppContainer(context: Context) {
    val settings = AppSettings(context.applicationContext)
    val engine = ThermalEngine(context.applicationContext, settings)
    val updater = AppUpdater(context.applicationContext)
}
