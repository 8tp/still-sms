package dev.chuds.stillsms.notif

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.RemoteInput
import dev.chuds.stillsms.sms.SmsSender

/**
 * RemoteInput-driven inline reply. The system delivers the user's typed text via
 * RemoteInput.getResultsFromIntent(); we hand it to SmsSender and dismiss the
 * heads-up.
 */
class QuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(NewMessageNotifier.EXTRA_THREAD_ID, -1L)
        val address = intent.getStringExtra(NewMessageNotifier.EXTRA_ADDRESS).orEmpty()
        if (threadId <= 0 || address.isBlank()) return

        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(NewMessageNotifier.REMOTE_INPUT_KEY)?.toString().orEmpty()
        if (text.isBlank()) return

        SmsSender.send(context.applicationContext, address, text)

        // Mark the thread read since the user clearly engaged with it.
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                values,
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }
        NewMessageNotifier.dismiss(context, threadId)
    }
}
