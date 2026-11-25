package dev.dawitsh.noroshi.core.model

import dev.dawitsh.noroshi.core.codec.XmlNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Data usage on ASR / Marvell "Duster" panels (the Stellar 8400 CPE).
 *
 * The router counts the SIM's traffic itself, in `file=statistics` under
 * `RGW/statistics/WanStatistics`, and can count it against a limit. The panel page that
 * sets this up (`js/panel/internet/traffic_setting.js`) is hidden on this device, but the
 * firmware answers it. Sizes are bytes, and the panel's KB / MB / GB are powers of 1024.
 */

private const val KB = 1024L
private const val MB = KB * 1024
const val GB = MB * 1024
private const val TB = GB * 1024

/** Bytes the way the panel and the operator write them: 1 GB = 1024 MB. */
fun dataSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    fun f(value: Double, unit: String) = String.format(Locale.US, "%.2f %s", value, unit)
    return when {
        b >= TB -> f(b / TB.toDouble(), "TB")
        b >= GB -> f(b / GB.toDouble(), "GB")
        b >= MB -> f(b / MB.toDouble(), "MB")
        b >= KB -> f(b / KB.toDouble(), "KB")
        else -> "$b B"
    }
}

/** Whole gigabytes (or a decimal) to bytes, as the panel does: `GB * 1024^3`, floored. */
fun gbToBytes(gb: Double): Long = Math.floor(gb * GB).toLong()

/** `stat_mang_method`: what the router counts against. Empty on a router that was never set up. */
enum class CountMode(val code: String) {
    Off("0"), Month("1"), Period("2"), Unlimited("3");

    companion object {
        fun parse(raw: String): CountMode? = entries.firstOrNull { it.code == raw.trim() }
    }
}

data class MonthUsage(val year: Int, val month: Int, val bytes: Long)

/** A bundle and how much of it is used. [end] is the day the bundle expires. */
data class BundleUsage(
    val size: Long,
    val used: Long,
    val start: LocalDate?,
    val end: LocalDate?,
) {
    val left: Long get() = (size - used).coerceAtLeast(0)

    val percent: Double get() = if (size <= 0) 0.0 else used * 100.0 / size

    /** Days until [end]: 0 on the day it ends, negative after. */
    fun daysLeft(today: LocalDate): Long? = end?.let { ChronoUnit.DAYS.between(today, it) }

    /** Average use per day since [start], counting today as a day. */
    fun perDay(today: LocalDate): Long? {
        val from = start ?: return null
        val days = ChronoUnit.DAYS.between(from, today) + 1
        if (days <= 0) return null
        return used / days
    }

    /** The day the bundle runs out at the pace so far; null with no pace yet, or nothing left. */
    fun runsOutOn(today: LocalDate): LocalDate? {
        if (left <= 0) return null
        val pace = perDay(today)?.takeIf { it > 0 } ?: return null
        return today.plusDays(left / pace)
    }

    /** Names this bundle, so each alert fires once per bundle. */
    val key: String get() = "$size|${start ?: ""}|${end ?: ""}"
}

/** `file=statistics`, read the way `traffic_setting.js` reads it. */
data class AsrTraffic(
    val mode: CountMode?,
    val usedMonth: Long?,
    val usedPeriod: Long?,
    val usedUnlimited: Long?,
    val sizeMonth: Long?,
    val sizePeriod: Long?,
    val sizeUnlimited: Long?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    /** `dis_value_at_upper`: 1 cuts the internet at the limit. */
    val cutOffRaw: String,
    val history: List<MonthUsage>,
) {
    val cutOff: Boolean get() = cutOffRaw.trim() == "1"

    /**
     * The bundle the router counts against, or null when none is set. A monthly limit runs
     * from the 1st to the 1st; an "unlimit" one has no dates.
     */
    fun bundle(today: LocalDate): BundleUsage? = when (mode) {
        CountMode.Period -> sizePeriod?.takeIf { it > 0 }?.let { BundleUsage(it, usedPeriod ?: 0, periodStart, periodEnd) }
        CountMode.Month -> sizeMonth?.takeIf { it > 0 }?.let {
            val first = today.withDayOfMonth(1)
            BundleUsage(it, usedMonth ?: 0, first, first.plusMonths(1))
        }

        CountMode.Unlimited -> sizeUnlimited?.takeIf { it > 0 }?.let { BundleUsage(it, usedUnlimited ?: 0, null, null) }
        CountMode.Off, null -> null
    }

    /**
     * A count that only goes up, for "today": the "unlimit" total, which the app never
     * corrects. Falls back to the period and month counts.
     */
    val lifetime: Long? get() = usedUnlimited ?: usedPeriod ?: usedMonth

    companion object {
        fun parse(root: XmlNode): AsrTraffic {
            val w = root.find("WanStatistics") ?: root
            fun long(name: String): Long? = w.textOf(name).trim().toLongOrNull()?.takeIf { it >= 0 }
            return AsrTraffic(
                mode = CountMode.parse(w.textOf("stat_mang_method")),
                usedMonth = long("total_used_month"),
                usedPeriod = long("total_used_period"),
                usedUnlimited = long("total_used_unlimit"),
                sizeMonth = long("total_available_month"),
                sizePeriod = long("total_available_period"),
                // The firmware spells this one "avaliable".
                sizeUnlimited = long("total_avaliable_unlimit") ?: long("total_available_unlimit"),
                periodStart = parseRouterDate(w.textOf("period_start_date")),
                periodEnd = parseRouterDate(w.textOf("period_end_date")),
                cutOffRaw = w.textOf("dis_value_at_upper"),
                history = (w.find("history_used_list")?.children ?: emptyList()).filter { it.name == "Item" }.mapNotNull { item ->
                    val (y, m) = item.at("date").split('-').map { it.trim().toIntOrNull() }.let {
                        (it.getOrNull(0) ?: return@mapNotNull null) to (it.getOrNull(1) ?: return@mapNotNull null)
                    }
                    // And this one "vale".
                    val bytes = (item.at("history_vale").ifEmpty { item.at("history_value") }).trim().toLongOrNull()
                        ?: return@mapNotNull null
                    MonthUsage(y, m, bytes)
                },
            )
        }
    }
}

/** `period_start_date` / `period_end_date` are "yy,m,d" (the panel prefixes "20" to the year). */
fun parseRouterDate(raw: String): LocalDate? {
    val parts = raw.trim().split(',').map { it.trim().toIntOrNull() }
    if (parts.size < 3 || parts.any { it == null }) return null
    return try {
        LocalDate.of(2000 + parts[0]!!, parts[1]!!, parts[2]!!)
    } catch (_: Exception) {
        null
    }
}

/** The panel's own format for a period date: two-digit year, month and day unpadded ("26,11,25"). */
fun routerDate(date: LocalDate): String =
    String.format(Locale.US, "%02d,%d,%d", date.year % 100, date.monthValue, date.dayOfMonth)

private const val STATS = "RGW/statistics/WanStatistics"

/** Why a bundle can't be written. */
enum class BundleProblem { NoSize, EndBeforeStart, TodayOutside, UsedTooBig }

/**
 * A bundle to count against: [size] bytes from [start] until [end] (the day it expires), with
 * [used] already used (null leaves the router's count as it is).
 */
data class BundlePlan(val size: Long, val start: LocalDate, val end: LocalDate, val used: Long?) {
    /** The panel's own checks: start before end, and today inside the period. */
    fun problem(today: LocalDate): BundleProblem? = when {
        size <= 0 -> BundleProblem.NoSize
        !end.isAfter(start) -> BundleProblem.EndBeforeStart
        today.isBefore(start) || today.isAfter(end) -> BundleProblem.TodayOutside
        used != null && (used < 0 || used > size) -> BundleProblem.UsedTooBig
        else -> null
    }

    /**
     * The `file=statistics` writes, one call each, exactly as the panel's buttons send them:
     * count per period, the period dates, the bundle size, then the corrected used amount.
     * Never the cut-off at the limit.
     */
    fun writes(): List<List<Pair<String, String>>> {
        // Not inside buildList: there `size` would be the list's own size.
        val bytes = size
        val writes = mutableListOf<List<Pair<String, String>>>()
        writes += listOf("$STATS/set_action" to "set_mang_method", "$STATS/stat_mang_method" to CountMode.Period.code)
        writes += listOf(
            "$STATS/set_action" to "set_stat_period",
            "$STATS/period_start_date" to routerDate(start),
            "$STATS/period_end_date" to routerDate(end),
        )
        writes += listOf("$STATS/total_available_period" to bytes.toString())
        if (used != null) writes += correction(CountMode.Period, used)
        return writes
    }
}

/** Sets the used amount of the current count (`corrected_value_*`), the panel's "calibrate" button. */
fun correction(mode: CountMode, used: Long): List<Pair<String, String>> {
    val suffix = when (mode) {
        CountMode.Month -> "month"
        CountMode.Unlimited -> "unlimit"
        CountMode.Period, CountMode.Off -> "period"
    }
    return listOf("$STATS/set_action" to "set_stat_value_$suffix", "$STATS/corrected_value_$suffix" to used.toString())
}

/** Turns the cut-off at the limit off ("No Action"). The app never turns it on. */
val CUT_OFF_OFF: List<Pair<String, String>> =
    listOf("$STATS/set_action" to "set_dis_at_upper", "$STATS/dis_value_at_upper" to "0")

/** One device's use, from `file=device_management` `client_list`; null where the router's count is unusable. */
data class DeviceUsage(
    val name: String,
    val mac: String,
    val connected: Boolean,
    val connType: String,
    val month: Long?,
    val last3Days: Long?,
    val total: Long?,
)

/**
 * Per-device use. This firmware's per-device counters are broken for most Wi-Fi devices:
 * numbers near 2^64, and the same made-up value on every offline device. A count is kept only
 * when it is below everything the router ever counted ([cap]) and not shared by three or more
 * devices; the rest show as unknown.
 */
object AsrDeviceUsage {
    private val FIELDS = listOf(
        "rx_data_used", "tx_data_used",
        "rx_data_used_monthly", "tx_data_used_monthly",
        "rx_data_used_last3day", "tx_data_used_last3day",
    )

    fun parse(root: XmlNode, cap: Long?): List<DeviceUsage> {
        val limit = cap?.takeIf { it > 0 } ?: 1_000_000_000_000_000L
        val known = root.find("known_devices_list")?.children.orEmpty()
            .filter { it.name == "Item" }
            .associate { it.at("mac").uppercase() to decodeUri(it.at("name")) }
        val items = root.find("client_list")?.children.orEmpty().filter { it.name == "Item" && it.child("mac") != null }

        // Values that several devices share are the firmware's filler, not counts.
        val shared: Map<String, Set<String>> = FIELDS.associateWith { field ->
            items.map { it.at(field).trim() }.filter { it.isNotEmpty() && it != "0" }
                .groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
        }

        fun value(item: XmlNode, field: String): Long? {
            val raw = item.at(field).trim()
            if (raw in shared.getValue(field)) return null
            return raw.toLongOrNull()?.takeIf { it in 0..limit }
        }

        fun pair(item: XmlNode, rx: String, tx: String): Long? {
            val a = value(item, rx) ?: return null
            val b = value(item, tx) ?: return null
            return (a + b).takeIf { it <= limit }
        }

        return items.map { item ->
            val mac = item.at("mac").uppercase()
            val raw = decodeUri(item.at("name"))
            val name = raw.takeIf { it.isNotEmpty() && !it.equals(mac, ignoreCase = true) && it != "unkown_marvell" }
                ?: known[mac]?.takeIf { it.isNotEmpty() && it != "unkown_marvell" }
                ?: ""
            DeviceUsage(
                name = name,
                mac = mac,
                connected = item.at("status") == "1",
                connType = item.at("conn_type"),
                month = pair(item, "rx_data_used_monthly", "tx_data_used_monthly"),
                last3Days = pair(item, "rx_data_used_last3day", "tx_data_used_last3day"),
                total = pair(item, "rx_data_used", "tx_data_used"),
            )
        }
    }

    /** What the Data screen lists: devices online now, and others with a usable count this month. */
    fun shown(devices: List<DeviceUsage>): List<DeviceUsage> = devices
        .filter { it.connected || (it.month ?: 0) > 0 }
        .sortedWith(compareByDescending<DeviceUsage> { it.connected }.thenByDescending { it.month ?: -1L })
}
