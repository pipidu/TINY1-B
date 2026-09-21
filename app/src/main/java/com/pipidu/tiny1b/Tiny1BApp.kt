package com.pipidu.tiny1b

import android.app.Application

class Tiny1BApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
