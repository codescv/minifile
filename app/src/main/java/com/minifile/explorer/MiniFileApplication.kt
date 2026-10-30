package com.minifile.explorer

import android.app.Application
import com.minifile.explorer.di.AppContainer

class MiniFileApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
