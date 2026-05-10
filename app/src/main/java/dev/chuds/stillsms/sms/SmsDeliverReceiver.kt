package dev.chuds.stillsms.sms

/*
 * SmsDeliverReceiver — the bottom of the inbound SMS funnel.
 *
 * The default-SMS contract: when the user picks us as the default app, the framework
 * stops broadcasting SMS_RECEIVED and instead routes inbound PDUs to OUR SMS_DELIVER
 * receiver only. The system does NOT insert the message into content://sms for us.
 * That responsibility is ours — failure to insert means inbound SMS silently vanishes.
 *
 * Block-list filter happens BEFORE the provider write so that blocked messages leave
 * no trace at all (no row, no notification). Matched on the contact-resolver-normalized
 * E.164 form to match what the user typed in the BlockListScreen.
 */

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import dev.chuds.stillsms.data.BlockListRepository
import dev.chuds.stillsms.notif.NewMessageNotifier

class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Same originating-address per delivery → coalesce multi-part bodies into one row.
        val sender = messages.first().originatingAddress ?: ""
        val timestamp = messages.first().timestampMillis
        val body = buildString {
            for (msg in messages) append(msg.messageBody.orEmpty())
        }

        val blockList = BlockListRepository(context)
        if (blockList.isBlocked(sender)) return

        val threadId = runCatching {
            if (sender.isNotBlank()) Telephony.Threads.getOrCreateThreadId(context, sender) else -1L
        }.getOrDefault(-1L)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        runCatching {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        }

        if (threadId > 0 && body.isNotBlank()) {
            // Best-effort sender display; ContactNameResolver is process-cached so this is
            // cheap even when the broadcast wakes a cold process.
            val name = sender
            NewMessageNotifier.post(
                context = context,
                threadId = threadId,
                sender = name.ifBlank { "(unknown)" },
                preview = body.take(280),
            )
        }
    }
}
