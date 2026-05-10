package dev.chuds.stillsms.mms

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Build an M-Send.req PDU for `SmsManager.sendMultimediaMessage()`.
 *
 * We hand-roll this rather than depend on the platform's hidden PduComposer
 * (it's @hide and reflection breaks across OEM forks). The encoder covers
 * exactly the field set the carrier handshake requires and nothing more:
 *   X-Mms-Message-Type     M-Send.req
 *   X-Mms-Transaction-ID   random
 *   X-Mms-MMS-Version      1.0
 *   From                   insert-address-token (carrier fills in)
 *   To                     <recipient>/TYPE=PLMN
 *   Content-Type           application/vnd.wap.multipart.related; type=...; start=...
 *   <body>                 SMIL part + (optional) text part + image part
 *
 * Wire format references: OMA-WAP-MMS-ENC-V1_3 §7 (binary encoding) and
 * OMA-WAP-WSP-V1_0 §8.4 (WSP primitives).
 */
internal object MmsPduEncoder {

    /** A single body part (SMIL, text/plain, image/jpeg, image/png). */
    data class Part(
        val contentTypeCode: Int?,         // WSP well-known code, OR null if customCt is used
        val customContentType: String?,    // raw mime string when contentTypeCode is null
        val contentId: String,             // "<smil>", "<txt>", "<img>"
        val contentLocation: String?,      // optional file-name hint (e.g., "image.jpg")
        val data: ByteArray,
    )

    /**
     * Build a complete M-Send.req PDU.
     *
     * @param recipient destination phone number, E.164 (e.g., "+15551234567"). The encoder
     *                  appends "/TYPE=PLMN" per the MMS spec.
     * @param subject   optional subject; omitted from the header when null/blank.
     * @param parts     ordered list of body parts. The first part is the SMIL "root" by
     *                  convention; whatever you supply will be referenced by the
     *                  Content-Type's start= parameter via the Content-ID of parts[0].
     * @param transactionId random ASCII string; reuse it on the M-Acknowledge.ind.
     */
    fun encodeSendReq(
        recipient: String,
        subject: String?,
        parts: List<Part>,
        transactionId: String = UUID.randomUUID().toString().replace("-", "").take(16),
    ): ByteArray {
        require(parts.isNotEmpty()) { "M-Send.req needs at least one body part" }
        val out = ByteArrayOutputStream(2048)

        // ---------- header ----------
        appendHeaderShort(out, MmsField.MESSAGE_TYPE, MmsMessageType.M_SEND_REQ)
        appendHeaderTextString(out, MmsField.TRANSACTION_ID, transactionId)
        appendHeaderShort(out, MmsField.MMS_VERSION, MmsVersion.V1_0)

        // From: insert-address-token (encoded-string-value form: length=1, then short-int 0x81)
        out.write(MmsField.FROM)
        out.write(1)
        out.write(MMS_FROM_INSERT_TOKEN)

        // To: encoded-string-value (charset UTF-8) "<number>/TYPE=PLMN"
        appendHeaderEncodedString(out, MmsField.TO, "$recipient/TYPE=PLMN")

        if (!subject.isNullOrBlank()) {
            appendHeaderEncodedString(out, MmsField.SUBJECT, subject)
        }

        // Content-Type: multipart.related ; type="<root mime>" ; start="<root cid>"
        val rootCid = parts[0].contentId
        val rootCt = parts[0].customContentType ?: mimeForCode(parts[0].contentTypeCode!!)
        appendMultipartRelatedContentType(out, rootCt, rootCid)

        // ---------- body ----------
        // Body = uintvar(numEntries) followed by each part's
        //   uintvar(headersLen) uintvar(dataLen) <headers bytes> <data bytes>
        val body = ByteArrayOutputStream(2048)
        writeUintvar(body, parts.size.toLong())
        for (p in parts) {
            val headers = ByteArrayOutputStream(64)
            // Content-Type header (no field-code — first thing in the part headers).
            if (p.contentTypeCode != null) {
                headers.write(0x80 or p.contentTypeCode)
            } else {
                writeTextString(headers, p.customContentType!!)
            }
            // Content-ID: stored as text-string with field code 0xC0 ("Content-ID" WSP code 0x40 | 0x80).
            headers.write(0xC0)
            writeQuotedTextString(headers, p.contentId)
            if (p.contentLocation != null) {
                appendHeaderTextString(headers, MmsField.CONTENT_LOCATION, p.contentLocation)
            }

            val headerBytes = headers.toByteArray()
            writeUintvar(body, headerBytes.size.toLong())
            writeUintvar(body, p.data.size.toLong())
            body.write(headerBytes)
            body.write(p.data)
        }

        out.write(body.toByteArray())
        return out.toByteArray()
    }

    // --- header writers ---

    /** field-code byte then a short-int value (high-bit-set single byte). */
    private fun appendHeaderShort(out: ByteArrayOutputStream, field: Int, value: Int) {
        out.write(field)
        out.write(0x80 or (value and 0x7F))
    }

    /** field-code byte then a text-string (NUL-terminated ASCII). */
    private fun appendHeaderTextString(out: ByteArrayOutputStream, field: Int, value: String) {
        out.write(field)
        writeTextString(out, value)
    }

    /**
     * field-code byte then an encoded-string-value:
     *   value-length charset text-string
     * We always emit UTF-8 so non-ASCII subjects survive intact.
     */
    private fun appendHeaderEncodedString(out: ByteArrayOutputStream, field: Int, value: String) {
        out.write(field)
        val bytes = value.toByteArray(Charsets.UTF_8)
        // value-length = length-quote (0x1F) + uintvar(payloadLen)
        // payload = charset(long-int form) + text-string bytes (incl NUL)
        val payloadLen = 2 + bytes.size + 1   // 2-byte charset (0x81 0x6A) + bytes + NUL
        writeValueLength(out, payloadLen.toLong())
        // Long-int form for charset 106:
        //   first byte = 0x81 (length 1, high bit means "long-int follows")
        //   second byte = 0x6A
        out.write(0x81)
        out.write(WSP_CHARSET_UTF8)
        out.write(bytes)
        out.write(0)
    }

    /**
     * Build the Content-Type header for a multipart/related body.
     *
     * Long form Content-Type:
     *   field-code(0x84) value-length content-type-bytes
     * where content-type-bytes is:
     *   <ct as well-known short-int OR text-string> param* (each as a parameter triple)
     */
    private fun appendMultipartRelatedContentType(
        out: ByteArrayOutputStream,
        rootMime: String,
        startCid: String,
    ) {
        out.write(MmsField.CONTENT_TYPE)
        val payload = ByteArrayOutputStream(64)
        // Well-known content-type code for application/vnd.wap.multipart.related = 0x33,
        // emitted as a short-int (high bit set).
        payload.write(0x80 or 0x33)
        // type=<rootMime> — parameter code 0x89, value text-string
        payload.write(WspParam.TYPE)
        writeTextString(payload, rootMime)
        // start=<rootCid>
        payload.write(WspParam.START)
        writeQuotedTextString(payload, startCid)
        val payloadBytes = payload.toByteArray()
        writeValueLength(out, payloadBytes.size.toLong())
        out.write(payloadBytes)
    }

    // --- WSP primitives ---

    /** text-string: ASCII bytes terminated by 0x00. If first char > 0x7F, prepend 0x7F (quote). */
    private fun writeTextString(out: ByteArrayOutputStream, s: String) {
        if (s.isNotEmpty() && s[0].code > 0x7F) out.write(0x7F)
        for (c in s) out.write(c.code and 0xFF)
        out.write(0)
    }

    /**
     * Quoted-string per WSP §8.4.2.1: leading 0x22 (`"`), then the raw text bytes, then
     * a trailing 0x00 NUL. We use this for Content-IDs because some carriers (notably
     * T-Mobile US, Vodafone DE) reject Content-IDs that arrive as plain text-strings.
     * The caller passes the cid wrapped in <> (e.g. "<smil>"); we keep those angle
     * brackets inside the quoted content per RFC 2392.
     */
    private fun writeQuotedTextString(out: ByteArrayOutputStream, raw: String) {
        val s = if (raw.startsWith("<") && raw.endsWith(">")) raw else "<$raw>"
        out.write(0x22)
        for (c in s) out.write(c.code and 0xFF)
        out.write(0)
    }

    /**
     * value-length:
     *   for n in [0..30], emit one byte = n
     *   else emit 0x1F then uintvar(n)
     */
    private fun writeValueLength(out: ByteArrayOutputStream, len: Long) {
        if (len in 0..30) out.write(len.toInt())
        else { out.write(0x1F); writeUintvar(out, len) }
    }

    /**
     * uintvar: 7 bits per byte, continuation bit (0x80) set on all but the last.
     * Little-tricky: bytes are emitted MOST-SIGNIFICANT FIRST.
     */
    private fun writeUintvar(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "uintvar value must be >= 0" }
        if (value == 0L) { out.write(0); return }
        // Collect 7-bit groups MSB→LSB.
        val groups = ArrayList<Int>(8)
        var v = value
        while (v > 0) {
            groups.add((v and 0x7F).toInt())
            v = v ushr 7
        }
        for (i in groups.indices.reversed()) {
            val isLast = (i == 0)
            val byte = groups[i] or (if (isLast) 0x00 else 0x80)
            out.write(byte)
        }
    }

    private fun mimeForCode(code: Int): String = when (code) {
        WspContentType.IMAGE_JPEG -> "image/jpeg"
        WspContentType.IMAGE_PNG -> "image/png"
        WspContentType.IMAGE_GIF -> "image/gif"
        WspContentType.TEXT_PLAIN -> "text/plain"
        else -> error("unknown wsp content-type code 0x${code.toString(16).uppercase(Locale.ROOT)}")
    }
}
