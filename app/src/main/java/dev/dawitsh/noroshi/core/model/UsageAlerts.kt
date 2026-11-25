package dev.dawitsh.noroshi.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What to be told about, from Settings. */
data class AlertSettings(
    val enabled: Boolean = true,
    val percents: List<Int> = DEFAULT_PERCENTS,
    val endingDays: List<Int> = DEFAULT_ENDING_DAYS,
) {
    companion object {
        val DEFAULT_PERCENTS = listOf(50, 75, 90, 100)
        val DEFAULT_ENDING_DAYS = listOf(3, 1)

        /** "50, 75, 90,100" -> [50, 75, 90, 100]; anything that isn't a number in [range] is dropped. */
        fun parseList(text: String, range: IntRange): List<Int> =
            text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in range }.distinct().sorted()

        fun format(values: List<Int>): String = values.joinToString(", ")
    }
}

/** Which alerts already went out, and for which bundle. A new bundle starts from nothing. */
data class AlertMemory(
    val bundleKey: String = "",
    val percents: Set<Int> = emptySet(),
    val endingDays: Set<Int> = emptySet(),
)

sealed interface UsageAlert {
    val usage: BundleUsage

    /** The bundle passed [threshold] percent (the highest one passed since the last check). */
    data class Used(val threshold: Int, override val usage: BundleUsage) : UsageAlert

    /** The bundle ends in [days] days (0 = today). */
    data class Ending(val days: Long, override val usage: BundleUsage) : UsageAlert
}

data class AlertCheck(val alerts: List<UsageAlert>, val memory: AlertMemory)

/**
 * Decides which alerts are due. Each threshold fires once per bundle; when a check finds
 * several passed at once (say the first check after setting up is at 80%), only the highest
 * one is sent and the lower ones count as sent. The same goes for the "ends in N days" ones.
 */
fun checkAlerts(usage: BundleUsage?, settings: AlertSettings, memory: AlertMemory, today: LocalDate): AlertCheck {
    if (usage == null || !settings.enabled) return AlertCheck(emptyList(), memory)

    val mem = if (memory.bundleKey == usage.key) memory else AlertMemory(bundleKey = usage.key)
    val alerts = mutableListOf<UsageAlert>()

    val passed = settings.percents.filter { usage.percent >= it }
    val newPercents = passed.filter { it !in mem.percents }
    if (newPercents.isNotEmpty()) alerts += UsageAlert.Used(newPercents.max(), usage)

    val daysLeft = usage.daysLeft(today)
    val dueDays = if (daysLeft == null || daysLeft < 0) emptyList() else settings.endingDays.filter { daysLeft <= it }
    val newDays = dueDays.filter { it !in mem.endingDays }
    if (newDays.isNotEmpty() && daysLeft != null) alerts += UsageAlert.Ending(daysLeft, usage)

    return AlertCheck(alerts, mem.copy(percents = mem.percents + passed, endingDays = mem.endingDays + dueDays))
}

/**
 * Where "today" starts: the router's lifetime count at the first reading of the day. When the
 * first reading came late (the phone was away), "today" is only since that reading.
 */
data class DailyBaseline(val date: LocalDate, val counter: Long, val takenAt: LocalTime) {
    /** True when the reading was taken late enough that "today" misses part of the day. */
    val partial: Boolean get() = takenAt.isAfter(LocalTime.of(0, 30))

    fun usedSince(counter: Long): Long = (counter - this.counter).coerceAtLeast(0)

    companion object {
        /** Keeps [previous] while it is today's and the count hasn't gone back (a reset); else starts anew. */
        fun next(previous: DailyBaseline?, now: LocalDateTime, counter: Long): DailyBaseline =
            if (previous != null && previous.date == now.toLocalDate() && counter >= previous.counter) previous
            else DailyBaseline(now.toLocalDate(), counter, now.toLocalTime().withNano(0))
    }
}
