package dev.dawitsh.noroshi.core.data

import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.model.AsrTraffic
import dev.dawitsh.noroshi.core.model.BundlePlan
import dev.dawitsh.noroshi.core.model.BundleUsage
import dev.dawitsh.noroshi.core.model.CountMode
import dev.dawitsh.noroshi.core.model.DailyBaseline
import dev.dawitsh.noroshi.core.model.DeviceUsage
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.res
import dev.dawitsh.noroshi.core.network.AsrRouter
import dev.dawitsh.noroshi.core.network.Router
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDateTime

/** One reading for the Data screen. */
data class DataReport(
    val traffic: AsrTraffic,
    val bundle: BundleUsage?,
    /** Used since [todayFrom] (the first reading today). */
    val today: Long?,
    val todayFrom: DailyBaseline?,
    val devices: List<DeviceUsage>?,
    val readAt: LocalDateTime,
)

/**
 * Reads and writes the router's data count (Stellar only). Every read moves the start of
 * "today" on when a new day began, and keeps the last bundle reading for the widget.
 */
class DataUsageService(
    private val routers: RouterRepository,
    private val usage: UsageRepository,
    private val onReading: (LastReading?) -> Unit = {},
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val changesFlow = MutableSharedFlow<DataReport>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What the router says after each write, so an open Data screen shows it at once. */
    val changes: SharedFlow<DataReport> = changesFlow.asSharedFlow()

    suspend fun read(withDevices: Boolean): DataReport {
        val (traffic, devices) = routers.session {
            val asr = asAsr()
            val t = asr.traffic()
            t to (if (withDevices) asr.deviceUsage(t.lifetime) else null)
        }
        return finish(traffic, devices)
    }

    /** Writes [plan] to the router (count per period); returns what the router says afterwards. */
    suspend fun setBundle(plan: BundlePlan): DataReport =
        finish(routers.session { asAsr().setBundle(plan) }, null).also { changesFlow.tryEmit(it) }

    /** Sets the used amount of the bundle the router counts against now. */
    suspend fun correctUsed(used: Long): DataReport {
        val traffic = routers.session {
            val asr = asAsr()
            val mode = asr.traffic().mode?.takeIf { it != CountMode.Off } ?: CountMode.Period
            asr.correctUsed(mode, used)
        }
        return finish(traffic, null).also { changesFlow.tryEmit(it) }
    }

    private suspend fun finish(traffic: AsrTraffic, devices: List<DeviceUsage>?): DataReport {
        val now = clock()
        val bundle = traffic.bundle(now.toLocalDate())
        val lifetime = traffic.lifetime
        val base = lifetime?.let { usage.baseline(now, it) }
        val last = bundle?.let { LastReading(it.percent, it.used, it.size, it.end, now) }
        usage.saveLast(last)
        onReading(last)
        return DataReport(
            traffic = traffic,
            bundle = bundle,
            today = lifetime?.let { count -> base?.usedSince(count) },
            todayFrom = base,
            devices = devices,
            readAt = now,
        )
    }

    private fun Router.asAsr(): AsrRouter =
        this as? AsrRouter ?: throw RouterException(res(R.string.err_not_supported, res(type.label)))
}
