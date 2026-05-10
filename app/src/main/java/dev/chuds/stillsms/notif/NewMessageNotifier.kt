package dev.chuds.stillsms.notif

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.chuds.stillsms.MainActivity
import dev.chuds.stillsms.R

/**
 * Single entry point for posting a "new message" heads-up. The 0.1 surface is the
 * minimum that's responsible to ship as default-SMS — sender display, preview, tap to
 * open. RemoteInput reply lands in 0.2.
 */
object NewMessageNotifier {

    fun post(
        context: Context,
        threadId: Long,
        sender: String,
        preview: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS is asked at first launch; if the user denied, the post is
            // silently a no-op.
            val pm = context.packageManager
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        NotificationChannels.ensure(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_THREAD
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            threadId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_still_sms_launcher)
            .setContentTitle(sender)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openPi)
            .build()

        context.getSystemService<NotificationManager>()
            ?.notify(threadId.toInt(), notification)
    }

    fun dismiss(context: Context, threadId: Long) {
        context.getSystemService<NotificationManager>()?.cancel(threadId.toInt())
    }
}
