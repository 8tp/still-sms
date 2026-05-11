package dev.chuds.stillsms.sms

/*
 * SmsSender — the outbound SMS path.
 *
 * Two reasons this is its own class rather than a method on ThreadRepository:
 *   1) The send path's correctness story lives entirely in PendingIntents and result
 *      receivers; it has no business with the cursor-renderer logic in ThreadRepository.
 *   2) RespondViaMessageService and QuickReplyReceiver both need to send without owning
 *      a UI Composable; a thin sender they can call from any context is much cleaner.
 *
 * Provider write order matters:
 *   - We insert the outbox row BEFORE handing the message to SmsManager so the UI sees
 *     it immediately. The sentIntent receiver flips MESSAGE_TYPE_QUEUED → SENT or FAILED.
 *   - For multi-part messages we insert ONE row carrying the joined body; the per-part
 *     PendingIntents share an EXTRA_MESSAGE_URI pointing back at it, and the receiver
 *     only marks it FAILED if any part hard-fails.
 *
 * No deliveredIntent in 0.2: the pact bans delivery receipts as a UI signal. We still
 * register a no-op DeliveredReceiver in the manifest so future 0.3 changes can opt into
 * "flag rows as failed if a hard NACK comes back without ever surfacing 'delivered'".
 */

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

object SmsSender {

    const val EXTRA_MESSAGE_URI = "still_sms.message_uri"
    const val EXTRA_PART_INDEX = "still_sms.part_index"
    const val EXTRA_PART_COUNT = "still_sms.part_count"

    /** Send the text. Returns the inserted outbox URI (or null if the insert failed). */
    fun send(
        context: Context,
        address: String,
        body: String,
    ): Uri? {
        if (address.isBlank() || body.isEmpty()) return null
        val ctx = context.applicationContext
        val resolver = ctx.contentResolver

        val now = System.currentTimeMillis()
        val threadId = runCatching {
            Telephony.Threads.getOrCreateThreadId(ctx, address)
        }.getOrDefault(-1L)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_QUEUED)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        val uri = runCatching {
            resolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        }.getOrNull() ?: return null

        runCatching {
            val sm = smsManager(ctx)
            val parts = sm.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(ctx, SmsSentReceiver::class.java).apply {
                    action = SmsSentReceiver.ACTION_SENT
                    data = uri
                    putExtra(EXTRA_MESSAGE_URI, uri.toString())
                    putExtra(EXTRA_PART_INDEX, i)
                    putExtra(EXTRA_PART_COUNT, parts.size)
                }
                sentIntents += PendingIntent.getBroadcast(
                    ctx,
                    uri.toString().hashCode() * 31 + i,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            if (parts.size == 1) {
                sm.sendTextMessage(address, null, parts[0], sentIntents[0], null)
            } else {
                sm.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            }
        }.onFailure {
            // Hard failure (e.g., no SIM or PendingIntent setup failure). Flip the
            // row immediately so it never sits forever as queued.
            markFailed(ctx, uri)
        }

        return uri
    }

    private fun smsManager(context: Context): SmsManager {
        // SmsManager.getDefault() is deprecated since API 31 in favor of context.getSystemService.
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    fun markFailed(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun markSent(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }
}
