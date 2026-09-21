package com.pipidu.tiny1b

import android.app.Application
import android.os.Looper
import android.util.Log

class Tiny1BApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "uncaught on ${thread.name}", error)
            runCatching { container.engine.onUncaught(thread, error) }
            val isMain = thread == Looper.getMainLooper().thread
            if (isMain) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        private const val TAG = "Tiny1BApp"
    }
}
