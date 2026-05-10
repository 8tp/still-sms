package dev.chuds.stillsms.mms

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Minimal inbound PDU decoder. Two entry points:
 *
 *   - [parseNotificationInd] reads an M-Notification.ind WAP push PDU and pulls out
 *     content-location, transaction-id, message-size, and from. That's all we need
 *     to schedule a [SmsManager.downloadMultimediaMessage] retrieval.
 *
 *   - [parseRetrieveConf] reads the M-Retrieve.conf the carrier MMSC sends back after
 *     a successful download. Returns the headers we care about plus the body parts
 *     (each with content-type + Content-ID + raw bytes), so the receiver can write
 *     rows to content://mms and content://mms/part.
 *
 * We do not implement every header WSP/MMS defines — only the subset still-sms uses.
 * Unknown fields are skipped using the value-length / text-string primitives.
 */
internal object MmsPduDecoder {

    data class NotificationHeaders(
        val transactionId: String?,
        val contentLocation: String?,
        val from: String?,
        val messageSize: Long?,
        val subject: String?,
    )

    data class RetrievePart(
        val contentType: String,
        val contentId: String?,
        val contentLocation: String?,
        val name: String?,
        val data: ByteArray,
    )

    data class RetrieveHeaders(
        val transactionId: String?,
        val from: String?,
        val to: List<String>,
        val subject: String?,
        val date: Long?,
        val messageId: String?,
        val parts: List<RetrievePart>,
    )

    fun parseNotificationInd(pdu: ByteArray): NotificationHeaders {
        val s = ByteArrayInputStream(pdu)
        var transactionId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        var subject: String? = null
        var messageSize: Long? = null

        while (s.available() > 0) {
            val field = s.read() and 0x7F or 0x80   // re-set high bit; field codes are 0x80-..
            when (field) {
                MmsField.MESSAGE_TYPE -> s.read()        // skip short-int
                MmsField.TRANSACTION_ID -> transactionId = readTextString(s)
                MmsField.MMS_VERSION -> s.read()
                MmsField.CONTENT_LOCATION -> contentLocation = readTextString(s)
                MmsField.MESSAGE_ID -> s.read().also { /* discard */ }; // not used
                MmsField.MESSAGE_CLASS -> s.read()
                MmsField.MESSAGE_SIZE -> messageSize = readLongInt(s)
                MmsField.EXPIRY -> skipValueLengthAndBody(s)
                MmsField.FROM -> from = readEncodedAddress(s)
                MmsField.SUBJECT -> subject = readEncodedString(s)
                MmsField.DELIVERY_REPORT, MmsField.READ_REPORT -> s.read()
                else -> { skipUnknown(s) }
            }
        }
        return NotificationHeaders(transactionId, contentLocation, from, messageSize, subject)
    }

    fun parseRetrieveConf(pdu: ByteArray): RetrieveHeaders {
        val s = ByteArrayInputStream(pdu)
        var transactionId: String? = null
        var from: String? = null
        val toList = ArrayList<String>(2)
        var subject: String? = null
        var date: Long? = null
        var messageId: String? = null

        // Headers are followed by the multipart body. The carrier sends headers in
        // any order; the body starts as soon as we see Content-Type because Content-Type
        // is required to be the LAST header before the body per MMS-ENC §7.3.
        var hitContentType = false
        while (!hitContentType && s.available() > 0) {
            val field = s.read() and 0x7F or 0x80
            when (field) {
                MmsField.MESSAGE_TYPE -> s.read()
                MmsField.TRANSACTION_ID -> transactionId = readTextString(s)
                MmsField.MMS_VERSION -> s.read()
                MmsField.MESSAGE_ID -> messageId = readTextString(s)
                MmsField.MESSAGE_CLASS -> s.read()
                MmsField.SUBJECT -> subject = readEncodedString(s)
                MmsField.FROM -> from = readEncodedAddress(s)
                MmsField.TO -> readEncodedString(s)?.let { toList += it }
                MmsField.DELIVERY_REPORT, MmsField.READ_REPORT -> s.read()
                MmsField.EXPIRY -> skipValueLengthAndBody(s)
                MmsField.CONTENT_TYPE -> {
                    skipContentTypeHeader(s)
                    hitContentType = true
                }
                else -> { skipUnknown(s) }
            }
        }

        val parts = if (hitContentType && s.available() > 0) {
            readMultipartBody(s)
        } else emptyList()

        return RetrieveHeaders(transactionId, from, toList, subject, date, messageId, parts)
    }

    /**
     * Body layout: uintvar(numParts) then for each part:
     *   uintvar(headersLen) uintvar(dataLen) <headers bytes> <data bytes>
     * The first thing in headers is the Content-Type (no field code).
     */
    private fun readMultipartBody(s: InputStream): List<RetrievePart> {
        val n = readUintvar(s).toInt()
        val out = ArrayList<RetrievePart>(n)
        repeat(n) {
            val hdrLen = readUintvar(s).toInt()
            val dataLen = readUintvar(s).toInt()
            val hdrBytes = ByteArray(hdrLen).also { s.read(it) }
            val dataBytes = ByteArray(dataLen).also { s.read(it) }

            val hs = ByteArrayInputStream(hdrBytes)
            val ct = readContentTypeAndParams(hs)
            var contentId: String? = null
            var contentLocation: String? = null
            while (hs.available() > 0) {
                val field = hs.read() and 0xFF
                when (field) {
                    0xC0 -> contentId = readTextString(hs)?.trim('<', '>')
                    MmsField.CONTENT_LOCATION -> contentLocation = readTextString(hs)
                    else -> skipUnknown(hs)
                }
            }
            out += RetrievePart(
                contentType = ct.first,
                contentId = contentId,
                contentLocation = contentLocation,
                name = ct.second,
                data = dataBytes,
            )
        }
        return out
    }

    /**
     * Read a Content-Type header value. The wire form can be:
     *   - short: a single byte 0x80..0xFF (well-known mime)
     *   - long:  value-length <ct-bytes>, where ct-bytes starts with the well-known
     *           short-int mime OR a text-string mime, followed by parameter pairs.
     *
     * Returns (mime, name?). We only extract the "name" parameter on the body side;
     * the rest are skipped.
     */
    private fun readContentTypeAndParams(s: InputStream): Pair<String, String?> {
        val first = s.read() and 0xFF
        if (first >= 0x80) {
            return Pair(decodeWellKnownMime(first and 0x7F), null)
        }
        // Long form.
        val payloadLen = if (first == 0x1F) readUintvar(s).toInt() else first
        val end = (s as ByteArrayInputStream).available() - (s.available() - payloadLen)
        // Read mime: short-int OR text-string.
        val mark = s.read() and 0xFF
        val mime: String = if (mark >= 0x80) decodeWellKnownMime(mark and 0x7F)
        else buildString {
            append(mark.toChar())
            var b = s.read()
            while (b > 0) { append(b.toChar()); b = s.read() }
        }
        var name: String? = null
        // Walk parameters until we've consumed payloadLen bytes (best-effort).
        // If we can't track precisely, stop on first decode failure.
        runCatching {
            while (s.available() > end) {
                val pcode = s.read() and 0xFF
                if (pcode == WspParam.NAME) name = readTextString(s)
                else if (pcode == WspParam.CHARSET) s.read()
                else if (pcode == WspParam.TYPE) readTextString(s)
                else if (pcode == WspParam.START) readTextString(s)
                else if (pcode == WspParam.BOUNDARY) readTextString(s)
                else readTextString(s)
            }
        }
        return Pair(mime, name)
    }

    /** Skip past the content-type header block (long-form only — we land here from parseRetrieveConf). */
    private fun skipContentTypeHeader(s: InputStream) {
        // We don't need the value; just consume value-length + body.
        skipValueLengthAndBody(s)
    }

    private fun decodeWellKnownMime(code: Int): String = when (code) {
        WspContentType.TEXT_PLAIN -> "text/plain"
        WspContentType.IMAGE_JPEG -> "image/jpeg"
        WspContentType.IMAGE_PNG -> "image/png"
        WspContentType.IMAGE_GIF -> "image/gif"
        0x33 -> "application/vnd.wap.multipart.related"
        0x21 -> "application/vnd.wap.multipart.mixed"
        0x22 -> "application/vnd.wap.multipart.alternative"
        WspContentType.APPLICATION_VND_WAP_MMS_MESSAGE -> "application/vnd.wap.mms-message"
        else -> "application/octet-stream"
    }

    // --- WSP primitives ---

    private fun readTextString(s: InputStream): String? {
        val sb = StringBuilder()
        var b = s.read()
        // First byte of 0x7F is a "quote" introducer.
        if (b == 0x7F) b = s.read()
        while (b > 0) {
            sb.append(b.toChar())
            b = s.read()
        }
        return if (sb.isEmpty() && b <= 0) null else sb.toString()
    }

    private fun readEncodedString(s: InputStream): String? {
        // value-length charset text-string  OR  text-string
        val first = s.read() and 0xFF
        if (first >= 0x20 && first < 0x80) {
            // Looks like first ASCII char of a bare text-string. Consume rest.
            val sb = StringBuilder().apply { append(first.toChar()) }
            var b = s.read()
            while (b > 0) { sb.append(b.toChar()); b = s.read() }
            return sb.toString()
        }
        val payloadLen = if (first == 0x1F) readUintvar(s).toInt() else first
        val end = if (s is ByteArrayInputStream) s.available() - payloadLen else -1
        // Skip charset (long-int form: 0x81 <code>, or short-int form).
        val charsetByte = s.read() and 0xFF
        if (charsetByte == 0x81) s.read()
        // The remaining bytes up to `end` are a NUL-terminated text-string.
        return readTextString(s)
    }

    private fun readEncodedAddress(s: InputStream): String? {
        // Same structure as encoded-string-value, with an "insert-address-token" sentinel.
        val first = s.read() and 0xFF
        val payloadLen = if (first == 0x1F) readUintvar(s).toInt() else first
        if (payloadLen == 1) { s.read(); return null }   // insert-address-token
        // Skip charset prefix if present.
        val charsetByte = s.read() and 0xFF
        if (charsetByte == 0x81) s.read()
        return readTextString(s)
    }

    private fun readLongInt(s: InputStream): Long {
        val len = s.read() and 0xFF
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (s.read().toLong() and 0xFF)
        return v
    }

    private fun readUintvar(s: InputStream): Long {
        var v = 0L
        var byte: Int
        do {
            byte = s.read()
            if (byte < 0) return v
            v = (v shl 7) or (byte and 0x7F).toLong()
        } while (byte and 0x80 != 0)
        return v
    }

    private fun skipValueLengthAndBody(s: InputStream) {
        val first = s.read() and 0xFF
        val len = if (first == 0x1F) readUintvar(s).toInt() else first
        s.skip(len.toLong())
    }

    /** Best-effort skip of an unknown header. We assume value is a text-string. */
    private fun skipUnknown(s: InputStream) {
        readTextString(s)
    }
}
