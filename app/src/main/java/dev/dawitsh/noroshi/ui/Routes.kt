package dev.dawitsh.noroshi.ui

import androidx.annotation.Keep
import androidx.annotation.StringRes
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.model.ReportKind
import kotlinx.serialization.Serializable

@Keep
enum class SmsAction(@StringRes val label: Int, val command: String) {
    Delete(R.string.delete, "delete"),
    Mark(R.string.mark_read, "mark"),
}

sealed interface Route {
    @Serializable
    data class Menu(val path: List<String> = emptyList()) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class Report(val kind: ReportKind) : Route

    @Serializable
    data object SmsInbox : Route

    @Serializable
    data object SmsShow : Route

    @Serializable
    data class SmsMessage(val id: Int) : Route

    @Serializable
    data class SendSms(val number: String = "") : Route

    @Serializable
    data class SmsSelect(val action: SmsAction) : Route

    @Serializable
    data class Ussd(val code: String = "") : Route
}

data class Crumb(val label: String, val target: Route? = null)

fun menuCrumbs(path: List<String>): List<Crumb> =
    listOf(Crumb("noroshi", Route.Menu())) + path.indices.map { i -> Crumb(path[i], Route.Menu(path.take(i + 1))) }

fun leafCrumbs(path: List<String>): List<Crumb> =
    menuCrumbs(path.dropLast(1)) + Crumb(path.last())
