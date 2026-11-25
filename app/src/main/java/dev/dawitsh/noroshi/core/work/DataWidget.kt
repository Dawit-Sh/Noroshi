package dev.dawitsh.noroshi.core.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.dawitsh.noroshi.MainActivity
import dev.dawitsh.noroshi.NoroshiApplication
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.data.LastReading
import dev.dawitsh.noroshi.core.model.dataSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor

/**
 * Home screen widget: the bundle's % used and what's left, from the last reading (the app or
 * the background check). It never logs in to the router itself.
 */
class DataWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val last = (context.applicationContext as NoroshiApplication).container.usageRepository.last.first()
                render(context, manager, ids, last)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** Redraws every placed widget with [last]. */
        fun refresh(context: Context, last: LastReading?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DataWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, last)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, last: LastReading?) {
            val views = RemoteViews(context.packageName, R.layout.widget_data)
            if (last == null) {
                views.setTextViewText(R.id.widget_percent, context.getString(R.string.dash))
                views.setProgressBar(R.id.widget_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_empty))
            } else {
                val percent = floor(last.percent).toInt()
                val left = dataSize((last.size - last.used).coerceAtLeast(0))
                val days = last.end?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
                val ends = when {
                    days == null -> null
                    days < 0 -> null
                    days == 0L -> context.getString(R.string.data_ends_today_short)
                    days == 1L -> context.getString(R.string.data_ends_tomorrow_short)
                    else -> context.resources.getQuantityString(R.plurals.data_ends_in_short, days.toInt(), days.toInt())
                }
                views.setTextViewText(R.id.widget_percent, "$percent%")
                views.setProgressBar(R.id.widget_bar, 100, percent.coerceIn(0, 100), false)
                val detail = if (ends != null) context.getString(R.string.widget_detail, left, ends)
                else context.getString(R.string.widget_detail_no_end, left, dataSize(last.size))
                views.setTextViewText(
                    R.id.widget_detail,
                    detail + "\n" + context.getString(R.string.widget_as_of, last.at.toLocalTime().format(TIME)),
                )
            }
            val open = Intent(context, MainActivity::class.java)
                .putExtra(UsageNotifier.EXTRA_OPEN, UsageNotifier.OPEN_DATA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
