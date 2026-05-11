package dev.chuds.stillsms.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import dev.chuds.stillsms.data.BlockListRepository
import dev.chuds.stillsms.data.ContactNameResolver
import dev.chuds.stillsms.notif.NewMessageNotifier
import java.io.File

/**
 * downloadedIntent target for SmsManager.downloadMultimediaMessage(). Once the carrier
 * MMSC handshake finishes, the system has written the M-Retrieve.conf bytes to the
 * cacheDir file we staged in MmsDeliverReceiver. We:
 *   1. Read the file, parse headers + body parts.
 *   2. Update the placeholder MMS row with the real address + subject + thread_id.
 *   3. Insert one /part row per body part. Image bytes go to the part's stream.
 *   4. Insert a content://mms/<id>/addr row for FROM (type=137).
 *   5. Fire a notification (reusing NewMessageNotifier) so the user sees it.
 *
 * Anything that throws here gets the placeholder row marked FAILED so the thread doesn't
 * sit stuck with an empty-body inbound bubble forever.
 */
class MmsDownloadReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DOWNLOADED = "dev.chuds.stillsms.MMS_DOWNLOADED"
        const val EXTRA_PLACEHOLDER_URI = "still_sms.mms.placeholder_uri"
        const val EXTRA_DOWNLOAD_FILE = "still_sms.mms.download_file"
        const val EXTRA_FROM = "still_sms.mms.from"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val placeholderUriString = intent.getStringExtra(EXTRA_PLACEHOLDER_URI) ?: return
        val placeholderUri = Uri.parse(placeholderUriString)
        val downloadPath = intent.getStringExtra(EXTRA_DOWNLOAD_FILE) ?: return
        val notifFrom = intent.getStringExtra(EXTRA_FROM)

        val file = File(downloadPath)
        if (resultCode != Activity.RESULT_OK || !file.exists() || file.length() == 0L) {
            markRetrieveFailed(ctx, placeholderUri, notifFrom)
            runCatching { file.delete() }
            return
        }

        val pdu = runCatching { file.readBytes() }.getOrNull() ?: run {
            markRetrieveFailed(ctx, placeholderUri, notifFrom)
            runCatching { file.delete() }
            return
        }
        val parsed = runCatching { MmsPduDecoder.parseRetrieveConf(pdu) }.getOrNull() ?: run {
            markRetrieveFailed(ctx, placeholderUri, notifFrom)
            runCatching { file.delete() }
            return
        }

        val mmsId = ContentUris.parseId(placeholderUri)
        val from = parsed.from ?: notifFrom
        if (BlockListRepository(ctx).isBlocked(from)) {
            runCatching { ctx.contentResolver.delete(placeholderUri, null, null) }
            runCatching { file.delete() }
            return
        }

        var threadId = -1L
        var snippet: String? = null

        try {
            // Update the placeholder with the address-derived thread id and subject.
            threadId = seedInboundMmsAddress(ctx, placeholderUri, from)
            val updated = ctx.contentResolver.update(
                placeholderUri,
                ContentValues().apply {
                    if (threadId > 0) put(Telephony.Mms.THREAD_ID, threadId)
                    if (!parsed.subject.isNullOrBlank()) put(Telephony.Mms.SUBJECT, parsed.subject)
                },
                null, null,
            )
            if (updated <= 0) error("MMS placeholder no longer exists")

            // Walk parts. SMIL is metadata; everything else is real content.
            for (part in parsed.parts) {
                val ct = part.contentType
                val ext = when {
                    ct.equals("image/jpeg", true) -> "jpg"
                    ct.equals("image/png", true) -> "png"
                    ct.equals("image/gif", true) -> "gif"
                    ct.equals("text/plain", true) -> "txt"
                    else -> "bin"
                }
                val partValues = ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, mmsId)
                    put(Telephony.Mms.Part.CONTENT_TYPE, ct)
                    if (part.contentId != null) put(Telephony.Mms.Part.CONTENT_ID, "<${part.contentId}>")
                    put(Telephony.Mms.Part.CONTENT_LOCATION, part.contentLocation ?: ("part.$ext"))
                    if (ct.equals("text/plain", true)) {
                        val text = String(part.data, Charsets.UTF_8)
                        put(Telephony.Mms.Part.CHARSET, 106)
                        put(Telephony.Mms.Part.TEXT, text)
                        if (snippet == null) snippet = text
                    }
                    if (part.name != null) put(Telephony.Mms.Part.NAME, part.name)
                }
                val partUri = ctx.contentResolver.insert(
                    Uri.parse("content://mms/$mmsId/part"), partValues,
                ) ?: error("MMS part insert failed")
                // Binary bodies need to be streamed to the part's openOutputStream.
                if (!ct.equals("text/plain", true)) {
                    val stream = ctx.contentResolver.openOutputStream(partUri)
                        ?: error("MMS part stream unavailable")
                    stream.use { it.write(part.data) }
                }
            }
        } catch (_: Exception) {
            markRetrieveFailed(ctx, placeholderUri, from)
            return
        } finally {
            // Done — clean up the staging file. Best-effort; harmless if it fails.
            runCatching { file.delete() }
        }

        if (!from.isNullOrBlank()) {
            val sender = ContactNameResolver(ctx).displayName(from) ?: from
            NewMessageNotifier.post(
                context = ctx,
                threadId = if (threadId > 0) threadId else mmsId,
                address = from,
                sender = sender,
                preview = snippet ?: "[image]",
            )
        }
    }

    private fun markRetrieveFailed(context: Context, placeholderUri: Uri, from: String?) {
        markInboundMmsRetrieveFailed(context, placeholderUri, from)
    }
}
