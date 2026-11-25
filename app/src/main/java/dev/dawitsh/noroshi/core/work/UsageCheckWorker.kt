package dev.dawitsh.noroshi.core.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dawitsh.noroshi.NoroshiApplication
import dev.dawitsh.noroshi.core.model.Feature
import dev.dawitsh.noroshi.core.model.checkAlerts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The background check: every 15 minutes (Android's shortest), when the router answers, read
 * its count and post the alerts that are due. Away from the router's network it does nothing.
 * Logging in ends any panel session open in a browser, as every login on this router does.
 */
class UsageCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NoroshiApplication).container
        val settings = container.settingsRepository.settings.first()
        if (!settings.type.has(Feature.Data)) return Result.success()
        val alertSettings = container.usageRepository.alertSettings.first()
        if (!alertSettings.enabled) return Result.success()
        if (!reachable(settings.router)) return Result.success()

        val report = try {
            container.dataUsage.read(withDevices = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "usage check failed: ${e.message}")
            return Result.success()
        }

        // Without permission nothing is recorded as sent, so the alert still comes once it is allowed.
        if (!UsageNotifier.canPost(applicationContext)) return Result.success()
        val today = report.readAt.toLocalDate()
        val check = checkAlerts(report.bundle, alertSettings, container.usageRepository.memory(), today)
        check.alerts.forEach { UsageNotifier.post(applicationContext, it, today) }
        container.usageRepository.saveMemory(check.memory)
        return Result.success()
    }

    /** A quick look for the router before logging in, so a check away from home costs two seconds at most. */
    private suspend fun reachable(address: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = address.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "80").toIntOrNull() ?: 80
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "NoroshiUsage"
        private const val NAME = "noroshi-usage-check"

        /** Starts the check every 15 minutes, or stops it. */
        fun schedule(context: Context, run: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!run) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UsageCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
