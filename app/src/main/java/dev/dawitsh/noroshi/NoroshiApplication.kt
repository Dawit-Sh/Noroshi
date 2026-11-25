package dev.dawitsh.noroshi

import android.app.Application
import dev.dawitsh.noroshi.core.model.Feature
import dev.dawitsh.noroshi.core.work.UsageCheckWorker
import dev.dawitsh.noroshi.di.AppContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class NoroshiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // The data check runs while the router type has a Data screen and the alerts are on.
        container.applicationScope.launch {
            combine(container.settingsRepository.settings, container.usageRepository.alertSettings) { settings, alerts ->
                settings.type.has(Feature.Data) && alerts.enabled
            }.distinctUntilChanged().collect { run -> UsageCheckWorker.schedule(this@NoroshiApplication, run) }
        }
    }
}
