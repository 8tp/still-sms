package dev.chuds.stillsms.mms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import dev.chuds.stillsms.mms.MmsPduEncoder.Part
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Outbound MMS path. The shape mirrors SmsSender:
 *   1. Insert provider rows (mms + part + addr) so the UI sees the outbound message
 *      immediately. We mark the message box as MESSAGE_BOX_OUTBOX up front; the sentIntent
 *      receiver flips it to SENT or FAILED.
 *   2. Encode an M-Send.req PDU and write it to cacheDir/mms_outbox/<uuid>.dat.
 *   3. Hand a content:// URI (FileProvider) to SmsManager.sendMultimediaMessage along with
 *      a PendingIntent the receiver listens for.
 *
 * The carrier MMSC handshake is what actually ships the bytes; we own the wire-format work
 * up to the point SmsManager takes the PDU. No INTERNET permission is required because
 * SmsManager talks to the modem via its system-side IPC, not through our network stack.
 */
object MmsSender {

    const val ACTION_SENT = "dev.chuds.stillsms.MMS_SENT"
    const val EXTRA_MESSAGE_URI = "still_sms.mms_uri"

    /**
     * Send an MMS to [address] with optional [body] text and a single [imageUri] attachment.
     * Returns the inserted content://mms URI, or null if the provider insert failed.
     */
    suspend fun send(
        context: Context,
        address: String,
        body: String?,
        imageUri: Uri,
    ): Uri? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (address.isBlank()) return@withContext null
        val ctx = context.applicationContext

        val imageBytes = readUriBytes(ctx, imageUri) ?: return@withContext null
        val mimeType = sniffImageMime(imageBytes) ?: "image/jpeg"

        val now = System.currentTimeMillis() / 1000   // mms.date is in seconds
        val threadId = runCatching {
            Telephony.Threads.getOrCreateThreadId(ctx, address)
        }.getOrDefault(-1L)

        // 1. Insert the placeholder MMS message row.
        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.DATE, now)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.MESSAGE_TYPE, MmsMessageType.M_SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, MmsVersion.V1_0)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            if (threadId > 0) put(Telephony.Mms.THREAD_ID, threadId)
            if (!body.isNullOrBlank()) put(Telephony.Mms.SUBJECT, "")
        }
        val mmsUri = ctx.contentResolver.insert(Telephony.Mms.CONTENT_URI, mmsValues)
            ?: return@withContext null
        val mmsId = ContentUris.parseId(mmsUri)

        // 2. Insert the recipient address row (TO=151).
        val addrValues = ContentValues().apply {
            put("address", address)
            put("type", 151)             // PduHeaders.TO
            put("charset", 106)          // UTF-8
            put("msg_id", mmsId)
        }
        runCatching {
            ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)
        }

        // 3. Insert body parts so renderers see them immediately. SMIL part is implied by
        //    the multipart/related content-type and isn't strictly needed in the provider
        //    rows for our own UI; we still write a text part if there's a caption, plus
        //    an image part with the local image bytes.
        if (!body.isNullOrBlank()) {
            insertTextPart(ctx, mmsId, body)
        }
        insertImagePart(ctx, mmsId, mimeType, imageBytes)

        // 4. Build the wire-format M-Send.req PDU and stage it in cacheDir.
        val parts = buildList<Part> {
            add(buildSmilPart(hasText = !body.isNullOrBlank(), imageName = "image", imageMime = mimeType))
            if (!body.isNullOrBlank()) add(buildTextPart(body))
            add(buildImagePart(imageBytes, mimeType))
        }
        val pduBytes = MmsPduEncoder.encodeSendReq(
            recipient = address,
            subject = null,
            parts = parts,
        )

        val pduFile = stagePduFile(ctx, pduBytes)
        val pduUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", pduFile)
        ctx.grantUriPermission("com.android.phone", pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // 5. Hand off to SmsManager. The sentIntent fires once the carrier handshake
        //    resolves (success or failure) — see MmsSentReceiver.
        val sentIntent = Intent(ctx, MmsSentReceiver::class.java).apply {
            action = ACTION_SENT
            data = mmsUri
            putExtra(EXTRA_MESSAGE_URI, mmsUri.toString())
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            (mmsId * 31).toInt(),
            sentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        runCatching {
            mmsManager(ctx).sendMultimediaMessage(ctx, pduUri, null, null, pi)
        }.onFailure {
            markFailed(ctx, mmsUri)
        }

        mmsUri
    }

    fun markFailed(context: Context, uri: Uri) {
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
        }
        runCatching { context.contentResolver.update(uri, v, null, null) }
    }

    fun markSent(context: Context, uri: Uri) {
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
        }
        runCatching { context.contentResolver.update(uri, v, null, null) }
    }

    // --- provider writes ---

    private fun insertTextPart(context: Context, mmsId: Long, text: String) {
        val v = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.CONTENT_ID, "<text>")
            put(Telephony.Mms.Part.CONTENT_LOCATION, "text.txt")
            put(Telephony.Mms.Part.CHARSET, 106)
            put(Telephony.Mms.Part.TEXT, text)
        }
        runCatching {
            context.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), v)
        }
    }

    private fun insertImagePart(context: Context, mmsId: Long, mime: String, bytes: ByteArray) {
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val v = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, mime)
            put(Telephony.Mms.Part.CONTENT_ID, "<image>")
            put(Telephony.Mms.Part.CONTENT_LOCATION, "image.$ext")
            put(Telephony.Mms.Part.NAME, "image.$ext")
        }
        val partUri = runCatching {
            context.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), v)
        }.getOrNull() ?: return
        runCatching {
            context.contentResolver.openOutputStream(partUri)?.use { it.write(bytes) }
        }
    }

    // --- pdu staging ---

    private fun stagePduFile(context: Context, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "mms_outbox").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.dat")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    // --- pdu parts ---

    private fun buildSmilPart(hasText: Boolean, imageName: String, imageMime: String): Part {
        val ext = when (imageMime) {
            "image/png" -> "png"; "image/gif" -> "gif"; else -> "jpg"
        }
        // Bare-minimum SMIL the major US carriers all parse without complaint.
        val smil = buildString {
            append("<smil><head><layout><root-layout/>")
            append("<region id=\"Image\" left=\"0\" top=\"0\" width=\"100%\" height=\"100%\" fit=\"meet\"/>")
            if (hasText) append("<region id=\"Text\" left=\"0\" top=\"70%\" width=\"100%\" height=\"30%\" fit=\"meet\"/>")
            append("</layout></head><body><par dur=\"5000ms\">")
            append("<img src=\"$imageName.$ext\" region=\"Image\"/>")
            if (hasText) append("<text src=\"text.txt\" region=\"Text\"/>")
            append("</par></body></smil>")
        }
        return Part(
            contentTypeCode = null,
            customContentType = "application/smil",
            contentId = "<smil>",
            contentLocation = "smil.xml",
            data = smil.toByteArray(Charsets.UTF_8),
        )
    }

    private fun buildTextPart(body: String): Part = Part(
        contentTypeCode = WspContentType.TEXT_PLAIN,
        customContentType = null,
        contentId = "<text>",
        contentLocation = "text.txt",
        data = body.toByteArray(Charsets.UTF_8),
    )

    private fun buildImagePart(bytes: ByteArray, mime: String): Part {
        val (code, ext) = when (mime) {
            "image/png" -> WspContentType.IMAGE_PNG to "png"
            "image/gif" -> WspContentType.IMAGE_GIF to "gif"
            else -> WspContentType.IMAGE_JPEG to "jpg"
        }
        return Part(
            contentTypeCode = code,
            customContentType = null,
            contentId = "<image>",
            contentLocation = "image.$ext",
            data = bytes,
        )
    }

    // --- helpers ---

    private fun readUriBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** Header sniff so we don't trust the picker's mime claim. JPEG/PNG/GIF cover the field. */
    private fun sniffImageMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val b = bytes
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
            b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte() && b[3] == 0x38.toByte() -> "image/gif"
            else -> null
        }
    }

    private fun mmsManager(context: Context): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /** Decode width/height of staged image (used by tests; harmless to keep here). */
    @Suppress("unused")
    fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }
}
