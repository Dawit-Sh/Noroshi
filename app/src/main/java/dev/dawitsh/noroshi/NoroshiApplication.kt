package dev.dawitsh.noroshi

import android.app.Application
import dev.dawitsh.noroshi.di.AppContainer

class NoroshiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
