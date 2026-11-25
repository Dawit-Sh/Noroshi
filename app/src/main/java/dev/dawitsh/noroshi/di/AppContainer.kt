package dev.dawitsh.noroshi.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.dawitsh.noroshi.NoroshiApplication
import dev.dawitsh.noroshi.core.data.DataUsageService
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.core.data.RouterRepository
import dev.dawitsh.noroshi.core.data.SettingsRepository
import dev.dawitsh.noroshi.core.data.UsageRepository
import dev.dawitsh.noroshi.core.data.settingsDataStore
import dev.dawitsh.noroshi.core.data.usageDataStore
import dev.dawitsh.noroshi.core.network.RouterClient
import dev.dawitsh.noroshi.core.work.DataWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(context.settingsDataStore)
    val routerRepository = RouterRepository(settingsRepository, RouterClient())
    val notices = NoticeCenter()
    val usageRepository = UsageRepository(context.usageDataStore)
    val dataUsage = DataUsageService(routerRepository, usageRepository, onReading = { DataWidget.refresh(context.applicationContext, it) })
}

val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NoroshiApplication).container
