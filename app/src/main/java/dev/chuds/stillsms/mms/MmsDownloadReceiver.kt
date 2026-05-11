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
import java.io.OutputStream

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

            snippet = persistMmsParts(
                parsed.parts,
                ContentResolverMmsPartSink(ctx, mmsId),
            )
        } catch (_: Exception) {
            markRetrieveFailed(ctx, placeholderUri, from)
            return
        } finally {
            // Done — clean up the staging file. Best-effort; harmless if it fails.
            runCatching { file.delete() }
        }

        notifyInboundMms(ctx, from, threadId, mmsId, snippet)
    }

    private fun notifyInboundMms(
        context: Context,
        from: String?,
        threadId: Long,
        mmsId: Long,
        snippet: String?,
    ) {
        if (from.isNullOrBlank()) return
        val sender = ContactNameResolver(context).displayName(from) ?: from
        NewMessageNotifier.post(
            context = context,
            threadId = if (threadId > 0) threadId else mmsId,
            address = from,
            sender = sender,
            preview = snippet ?: "[image]",
        )
    }

    private fun markRetrieveFailed(context: Context, placeholderUri: Uri, from: String?) {
        markInboundMmsRetrieveFailed(context, placeholderUri, from)
    }
}

internal data class MmsProviderPart(
    val contentType: String,
    val contentId: String?,
    val contentLocation: String,
    val name: String?,
    val text: String?,
    val binaryData: ByteArray?,
)

internal interface MmsPartSink<PartId> {
    fun insert(part: MmsProviderPart): PartId?
    fun openOutputStream(partId: PartId): OutputStream?
    fun delete(partId: PartId)
}

internal fun <PartId> persistMmsParts(
    parts: List<MmsPduDecoder.RetrievePart>,
    sink: MmsPartSink<PartId>,
): String? {
    val inserted = mutableListOf<PartId>()
    var snippet: String? = null
    try {
        for (part in parts) {
            val providerPart = part.toProviderPart()
            val partId = sink.insert(providerPart) ?: error("MMS part insert failed")
            inserted += partId
            providerPart.binaryData?.let { data ->
                val stream = sink.openOutputStream(partId) ?: error("MMS part stream unavailable")
                stream.use { it.write(data) }
            }
            if (snippet == null && providerPart.text != null) snippet = providerPart.text
        }
        return snippet
    } catch (e: Exception) {
        inserted.asReversed().forEach { partId ->
            runCatching { sink.delete(partId) }
        }
        throw e
    }
}

private fun MmsPduDecoder.RetrievePart.toProviderPart(): MmsProviderPart {
    val ext = when {
        contentType.equals("image/jpeg", true) -> "jpg"
        contentType.equals("image/png", true) -> "png"
        contentType.equals("image/gif", true) -> "gif"
        contentType.equals("text/plain", true) -> "txt"
        else -> "bin"
    }
    val text = if (contentType.equals("text/plain", true)) {
        String(data, Charsets.UTF_8)
    } else {
        null
    }
    return MmsProviderPart(
        contentType = contentType,
        contentId = contentId,
        contentLocation = contentLocation ?: "part.$ext",
        name = name,
        text = text,
        binaryData = if (text == null) data else null,
    )
}

private class ContentResolverMmsPartSink(
    private val context: Context,
    private val mmsId: Long,
) : MmsPartSink<Uri> {
    override fun insert(part: MmsProviderPart): Uri? =
        context.contentResolver.insert(
            Uri.parse("content://mms/$mmsId/part"),
            ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mmsId)
                put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                if (part.contentId != null) put(Telephony.Mms.Part.CONTENT_ID, "<${part.contentId}>")
                put(Telephony.Mms.Part.CONTENT_LOCATION, part.contentLocation)
                if (part.text != null) {
                    put(Telephony.Mms.Part.CHARSET, 106)
                    put(Telephony.Mms.Part.TEXT, part.text)
                }
                if (part.name != null) put(Telephony.Mms.Part.NAME, part.name)
            },
        )

    override fun openOutputStream(partId: Uri): OutputStream? =
        context.contentResolver.openOutputStream(partId)

    override fun delete(partId: Uri) {
        context.contentResolver.delete(partId, null, null)
    }
}
