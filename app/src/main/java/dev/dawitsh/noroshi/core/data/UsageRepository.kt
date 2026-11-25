package dev.dawitsh.noroshi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dawitsh.noroshi.core.model.AlertMemory
import dev.dawitsh.noroshi.core.model.AlertSettings
import dev.dawitsh.noroshi.core.model.DailyBaseline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(name = "usage")

/** The last bundle reading, kept for the home screen widget and "last checked". */
data class LastReading(val percent: Double, val used: Long, val size: Long, val end: LocalDate?, val at: LocalDateTime)

/** The data alerts' settings and memory, and the start of "today". Kept on the phone only. */
class UsageRepository(private val store: DataStore<Preferences>) {
    private val enabled = booleanPreferencesKey("alerts_enabled")
    private val percents = stringPreferencesKey("alert_percents")
    private val endingDays = stringPreferencesKey("alert_ending_days")

    private val memoryKey = stringPreferencesKey("alert_bundle")
    private val memoryPercents = stringPreferencesKey("alert_sent_percents")
    private val memoryDays = stringPreferencesKey("alert_sent_days")

    private val baseDate = stringPreferencesKey("today_date")
    private val baseCounter = longPreferencesKey("today_counter")
    private val baseTime = stringPreferencesKey("today_time")

    private val lastPercent = stringPreferencesKey("last_percent")
    private val lastUsed = longPreferencesKey("last_used")
    private val lastSize = longPreferencesKey("last_size")
    private val lastEnd = stringPreferencesKey("last_end")
    private val lastAt = stringPreferencesKey("last_at")

    val alertSettings: Flow<AlertSettings> = store.data.map { prefs ->
        val defaults = AlertSettings()
        AlertSettings(
            enabled = prefs[enabled] ?: defaults.enabled,
            percents = prefs[percents]?.let { AlertSettings.parseList(it, 1..100) } ?: defaults.percents,
            endingDays = prefs[endingDays]?.let { AlertSettings.parseList(it, 0..60) } ?: defaults.endingDays,
        )
    }

    suspend fun saveAlertSettings(settings: AlertSettings) {
        store.edit {
            it[enabled] = settings.enabled
            it[percents] = AlertSettings.format(settings.percents)
            it[endingDays] = AlertSettings.format(settings.endingDays)
        }
    }

    suspend fun memory(): AlertMemory = store.data.first().let { prefs ->
        AlertMemory(
            bundleKey = prefs[memoryKey] ?: "",
            percents = prefs[memoryPercents]?.let { AlertSettings.parseList(it, 0..1000).toSet() } ?: emptySet(),
            endingDays = prefs[memoryDays]?.let { AlertSettings.parseList(it, 0..1000).toSet() } ?: emptySet(),
        )
    }

    suspend fun saveMemory(memory: AlertMemory) {
        store.edit {
            it[memoryKey] = memory.bundleKey
            it[memoryPercents] = AlertSettings.format(memory.percents.sorted())
            it[memoryDays] = AlertSettings.format(memory.endingDays.sorted())
        }
    }

    /** Moves the start of "today" on at the first reading of a day, and returns it. */
    suspend fun baseline(now: LocalDateTime, counter: Long): DailyBaseline {
        var result: DailyBaseline? = null
        store.edit { prefs ->
            val previous = runCatching {
                DailyBaseline(LocalDate.parse(prefs[baseDate]!!), prefs[baseCounter]!!, LocalTime.parse(prefs[baseTime]!!))
            }.getOrNull()
            val next = DailyBaseline.next(previous, now, counter)
            prefs[baseDate] = next.date.toString()
            prefs[baseCounter] = next.counter
            prefs[baseTime] = next.takenAt.toString()
            result = next
        }
        return result!!
    }

    val last: Flow<LastReading?> = store.data.map { prefs ->
        runCatching {
            LastReading(
                percent = prefs[lastPercent]!!.toDouble(),
                used = prefs[lastUsed]!!,
                size = prefs[lastSize]!!,
                end = prefs[lastEnd]?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                at = LocalDateTime.parse(prefs[lastAt]!!),
            )
        }.getOrNull()
    }

    suspend fun saveLast(reading: LastReading?) {
        store.edit {
            if (reading == null) {
                listOf(lastPercent, lastEnd, lastAt).forEach { key -> it.remove(key) }
                it.remove(lastUsed)
                it.remove(lastSize)
            } else {
                it[lastPercent] = reading.percent.toString()
                it[lastUsed] = reading.used
                it[lastSize] = reading.size
                it[lastEnd] = reading.end?.toString() ?: ""
                it[lastAt] = reading.at.withNano(0).toString()
            }
        }
    }
}
