package dev.chuds.stillsms.notif

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService
import dev.chuds.stillsms.MainActivity
import dev.chuds.stillsms.R

/**
 * Single entry point for posting a "new message" heads-up.
 *
 * Surface (per spec, no more, no less):
 *   - Title  = sender name
 *   - Text   = message preview
 *   - Tap    = open the thread in MainActivity
 *   - reply  = inline RemoteInput → QuickReplyReceiver sends via SmsManager
 *   - mark read = MarkReadReceiver flips read=1 on every row in the thread
 *
 * The pact bans delivery and read receipts as visible state in the UI; consequently the
 * notification text never says "delivered", "read", or "seen".
 */
object NewMessageNotifier {

    const val EXTRA_THREAD_ID = "thread_id"
    const val EXTRA_ADDRESS = "address"
    const val REMOTE_INPUT_KEY = "still_sms.reply_text"

    fun post(
        context: Context,
        threadId: Long,
        address: String,
        sender: String,
        preview: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        NotificationChannels.ensure(context)

        val notification: Notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_still_sms_notification)
            .setContentTitle(sender)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openThreadIntent(context, threadId))
            .addAction(replyAction(context, threadId, address))
            .addAction(markReadAction(context, threadId))
            .build()

        context.getSystemService<NotificationManager>()
            ?.notify(threadId.toInt(), notification)
    }

    fun dismiss(context: Context, threadId: Long) {
        context.getSystemService<NotificationManager>()?.cancel(threadId.toInt())
    }

    private fun openThreadIntent(context: Context, threadId: Long): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_THREAD
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            threadId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun replyAction(
        context: Context,
        threadId: Long,
        address: String,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(context.getString(R.string.default_reply_label))
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_ADDRESS, address)
        }
        // FLAG_MUTABLE required so the system can attach RemoteInput results.
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            threadId.toInt() * 31,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun markReadAction(
        context: Context,
        threadId: Long,
    ): NotificationCompat.Action {
        val intent = Intent(context, MarkReadReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            threadId.toInt() * 17,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "mark read", pendingIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }
}
