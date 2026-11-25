package dev.dawitsh.noroshi.core.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dawitsh.noroshi.MainActivity
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.model.UsageAlert
import dev.dawitsh.noroshi.core.model.dataSize
import java.time.LocalDate
import kotlin.math.floor

/** Posts the data alerts. */
object UsageNotifier {
    const val CHANNEL = "data_usage"
    const val EXTRA_OPEN = "dev.dawitsh.noroshi.OPEN"
    const val OPEN_DATA = "data"

    private const val ID_USED = 4101
    private const val ID_ENDING = 4102

    /** Android 13+ asks for this at run time; older versions only have the app's switch. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canPost(context: Context): Boolean =
        !needsPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.data_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.data_channel_desc)
            }
        )
    }

    /** Title and text of [alert], in the app's words. */
    fun text(context: Context, alert: UsageAlert, today: LocalDate): Pair<String, String> {
        val u = alert.usage
        val percent = floor(u.percent).toInt()
        val days = u.daysLeft(today)
        val ends = when {
            days == null || days < 0 -> ""
            days == 0L -> context.getString(R.string.data_ends_today_short)
            days == 1L -> context.getString(R.string.data_ends_tomorrow_short)
            else -> context.resources.getQuantityString(R.plurals.data_ends_in_short, days.toInt(), days.toInt())
        }
        return when (alert) {
            is UsageAlert.Used -> {
                val title = if (u.used >= u.size) context.getString(R.string.data_alert_used_up)
                else context.getString(R.string.data_alert_used, percent)
                val body = context.getString(R.string.data_alert_used_body, dataSize(u.used), dataSize(u.left), dataSize(u.size))
                title to listOf(body, ends).filter { it.isNotEmpty() }.joinToString(" · ")
            }

            is UsageAlert.Ending -> {
                val title = when (alert.days) {
                    0L -> context.getString(R.string.data_alert_ends_today)
                    1L -> context.getString(R.string.data_alert_ends_tomorrow)
                    else -> context.resources.getQuantityString(R.plurals.data_alert_ends_in, alert.days.toInt(), alert.days.toInt())
                }
                title to context.getString(R.string.data_alert_ending_body, dataSize(u.left), dataSize(u.size), percent)
            }
        }
    }

    /** Posts [alert]; a newer alert of the same kind replaces the older one. Does nothing without permission. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, alert: UsageAlert, today: LocalDate): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val (title, body) = text(context, alert, today)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, OPEN_DATA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_noroshi)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(100, floor(alert.usage.percent).toInt().coerceIn(0, 100), false)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        val id = if (alert is UsageAlert.Used) ID_USED else ID_ENDING
        NotificationManagerCompat.from(context).notify(id, notification)
        return true
    }
}
