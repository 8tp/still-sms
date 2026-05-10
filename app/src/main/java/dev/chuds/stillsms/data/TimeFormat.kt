package dev.chuds.stillsms.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tiny formatter so timestamps render the same way across thread list, thread view,
 * and the eventual export. Two outputs: a short list-row form ("14:32" / "Mon" /
 * "Apr 12") and a long stamp for inside-thread captions ("2026-04-12 14:32").
 *
 * Honoring the user's "24-hour timestamps" toggle. Defaults to 24-hour.
 */
object TimeFormat {

    fun listRow(epochMs: Long, twentyFourHour: Boolean): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }

        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return clockOnly(epochMs, twentyFourHour)

        val daysAgo = ((now.timeInMillis - epochMs) / 86_400_000L).toInt()
        if (daysAgo in 0..6 && now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMs))
        }
        return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
        }
    }

    fun longStamp(epochMs: Long, twentyFourHour: Boolean): String {
        val pattern = if (twentyFourHour) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
    }

    fun clockOnly(epochMs: Long, twentyFourHour: Boolean): String {
        val pattern = if (twentyFourHour) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(epochMs))
    }
}
