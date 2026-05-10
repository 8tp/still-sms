package dev.chuds.stillsms.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dev.chuds.stillsms.R

object NotificationChannels {

    const val MESSAGES = "messages"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            MESSAGES,
            context.getString(R.string.messages_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.messages_channel_description)
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }
}
