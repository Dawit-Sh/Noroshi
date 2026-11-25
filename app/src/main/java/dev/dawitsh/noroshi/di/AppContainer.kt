package dev.dawitsh.noroshi.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.dawitsh.noroshi.NoroshiApplication
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.core.data.RouterRepository
import dev.dawitsh.noroshi.core.data.SettingsRepository
import dev.dawitsh.noroshi.core.data.settingsDataStore
import dev.dawitsh.noroshi.core.network.RouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(context.settingsDataStore)
    val routerRepository = RouterRepository(settingsRepository, RouterClient())
    val notices = NoticeCenter()
}

val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NoroshiApplication).container
