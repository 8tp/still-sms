package dev.chuds.stillsms.mms

import dev.chuds.stillsms.mms.MmsPduEncoder.Part
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsPduCodecTest {
    @Test
    fun parseNotificationInd_keepsAlignmentAfterMessageIdAndClass() {
        val pdu = buildByteArray {
            appendHeaderShort(MmsField.MESSAGE_TYPE, MmsMessageType.M_NOTIFICATION_IND)
            appendHeaderTextString(MmsField.TRANSACTION_ID, "notify-tx")
            appendHeaderShort(MmsField.MMS_VERSION, MmsVersion.V1_0)
            appendHeaderTextString(MmsField.MESSAGE_ID, "carrier-message-id")
            appendHeaderTextString(MmsField.MESSAGE_CLASS, "personal")
            appendHeaderTextString(MmsField.CONTENT_LOCATION, "https://mmsc.example/msg")
            appendHeaderLongInt(MmsField.MESSAGE_SIZE, 42)
            appendHeaderEncodedString(MmsField.FROM, "+15551234567")
            appendHeaderEncodedString(MmsField.SUBJECT, "hello")
        }

        val decoded = MmsPduDecoder.parseNotificationInd(pdu)

        assertEquals("notify-tx", decoded.transactionId)
        assertEquals("https://mmsc.example/msg", decoded.contentLocation)
        assertEquals("+15551234567", decoded.from)
        assertEquals(42L, decoded.messageSize)
        assertEquals("hello", decoded.subject)
    }

    @Test
    fun encodeSendReq_canBeDecodedAsMultipartWithInlineImage() {
        val text = "caption from still-sms"
        val parts = listOf(
            Part(
                contentTypeCode = null,
                customContentType = "application/smil",
                contentId = "<smil>",
                contentLocation = "smil.xml",
                data = "<smil><body><par><text src=\"text.txt\"/><img src=\"image.png\"/></par></body></smil>"
                    .toByteArray(Charsets.UTF_8),
            ),
            Part(
                contentTypeCode = WspContentType.TEXT_PLAIN,
                customContentType = null,
                contentId = "<text>",
                contentLocation = "text.txt",
                data = text.toByteArray(Charsets.UTF_8),
            ),
            Part(
                contentTypeCode = WspContentType.IMAGE_PNG,
                customContentType = null,
                contentId = "<image>",
                contentLocation = "image.png",
                data = tinyTransparentPng,
            ),
        )

        val pdu = MmsPduEncoder.encodeSendReq(
            recipient = "+15551234567",
            subject = "subject",
            parts = parts,
            transactionId = "tx123",
        )

        val decoded = MmsPduDecoder.parseRetrieveConf(pdu)

        assertEquals("tx123", decoded.transactionId)
        assertNull(decoded.from)
        assertEquals(listOf("+15551234567/TYPE=PLMN"), decoded.to)
        assertEquals("subject", decoded.subject)
        assertEquals(3, decoded.parts.size)

        assertEquals("application/smil", decoded.parts[0].contentType)
        assertEquals("smil", decoded.parts[0].contentId)
        assertEquals("smil.xml", decoded.parts[0].contentLocation)

        assertEquals("text/plain", decoded.parts[1].contentType)
        assertEquals("text", decoded.parts[1].contentId)
        assertEquals("text.txt", decoded.parts[1].contentLocation)
        assertEquals(text, String(decoded.parts[1].data, Charsets.UTF_8))

        assertEquals("image/png", decoded.parts[2].contentType)
        assertEquals("image", decoded.parts[2].contentId)
        assertEquals("image.png", decoded.parts[2].contentLocation)
        assertArrayEquals(tinyTransparentPng, decoded.parts[2].data)
    }

    @Test
    fun parseRetrieveConf_decodesLongFormPartContentTypeParams() {
        val pdu = buildRetrieveConf(
            partHeaders = buildPartHeadersWithNamedLongContentType(
                mime = "image/png",
                name = "inline.png",
                contentId = "<inline>",
                contentLocation = "inline.png",
            ),
            partData = tinyTransparentPng,
        )

        val decoded = MmsPduDecoder.parseRetrieveConf(pdu)

        assertEquals("+15551234567", decoded.from)
        assertEquals("image/png", decoded.parts.single().contentType)
        assertEquals("inline.png", decoded.parts.single().name)
        assertEquals("inline", decoded.parts.single().contentId)
        assertEquals("inline.png", decoded.parts.single().contentLocation)
        assertArrayEquals(tinyTransparentPng, decoded.parts.single().data)
    }

    private fun buildRetrieveConf(partHeaders: ByteArray, partData: ByteArray): ByteArray =
        buildByteArray {
            appendHeaderShort(MmsField.MESSAGE_TYPE, MmsMessageType.M_RETRIEVE_CONF)
            appendHeaderTextString(MmsField.TRANSACTION_ID, "rx123")
            appendHeaderShort(MmsField.MMS_VERSION, MmsVersion.V1_0)
            appendHeaderEncodedString(MmsField.FROM, "+15551234567")
            appendMultipartRelatedContentType(rootMime = "image/png", startCid = "<inline>")
            writeUintvar(1)
            writeUintvar(partHeaders.size)
            writeUintvar(partData.size)
            write(partHeaders)
            write(partData)
        }

    private fun buildPartHeadersWithNamedLongContentType(
        mime: String,
        name: String,
        contentId: String,
        contentLocation: String,
    ): ByteArray = buildByteArray {
        val contentType = buildByteArray {
            writeTextString(mime)
            write(WspParam.NAME)
            writeTextString(name)
        }
        writeValueLength(contentType.size)
        write(contentType)
        write(0xC0)
        writeQuotedTextString(contentId)
        appendHeaderTextString(MmsField.CONTENT_LOCATION, contentLocation)
    }

    private fun buildByteArray(block: java.io.ByteArrayOutputStream.() -> Unit): ByteArray =
        java.io.ByteArrayOutputStream().apply(block).toByteArray()

    private fun java.io.ByteArrayOutputStream.appendHeaderShort(field: Int, value: Int) {
        write(field)
        write(0x80 or (value and 0x7F))
    }

    private fun java.io.ByteArrayOutputStream.appendHeaderTextString(field: Int, value: String) {
        write(field)
        writeTextString(value)
    }

    private fun java.io.ByteArrayOutputStream.appendHeaderLongInt(field: Int, value: Long) {
        write(field)
        require(value >= 0)
        val bytes = ArrayList<Int>()
        var v = value
        do {
            bytes += (v and 0xFF).toInt()
            v = v ushr 8
        } while (v > 0)
        write(bytes.size)
        for (i in bytes.indices.reversed()) write(bytes[i])
    }

    private fun java.io.ByteArrayOutputStream.appendHeaderEncodedString(field: Int, value: String) {
        write(field)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val payloadLen = 2 + bytes.size + 1
        writeValueLength(payloadLen)
        write(0x81)
        write(WSP_CHARSET_UTF8)
        write(bytes)
        write(0)
    }

    private fun java.io.ByteArrayOutputStream.appendMultipartRelatedContentType(
        rootMime: String,
        startCid: String,
    ) {
        write(MmsField.CONTENT_TYPE)
        val payload = buildByteArray {
            write(0x80 or 0x33)
            write(WspParam.TYPE)
            writeTextString(rootMime)
            write(WspParam.START)
            writeQuotedTextString(startCid)
        }
        writeValueLength(payload.size)
        write(payload)
    }

    private fun java.io.ByteArrayOutputStream.writeTextString(value: String) {
        for (c in value) write(c.code and 0xFF)
        write(0)
    }

    private fun java.io.ByteArrayOutputStream.writeQuotedTextString(value: String) {
        write(0x22)
        for (c in value) write(c.code and 0xFF)
        write(0)
    }

    private fun java.io.ByteArrayOutputStream.writeValueLength(len: Int) {
        if (len in 0..30) write(len) else {
            write(0x1F)
            writeUintvar(len)
        }
    }

    private fun java.io.ByteArrayOutputStream.writeUintvar(value: Int) {
        require(value >= 0)
        if (value == 0) {
            write(0)
            return
        }
        val groups = ArrayList<Int>()
        var v = value
        while (v > 0) {
            groups += v and 0x7F
            v = v ushr 7
        }
        for (i in groups.indices.reversed()) {
            write(groups[i] or if (i == 0) 0 else 0x80)
        }
    }

    private val tinyTransparentPng: ByteArray = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
        0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
        0x42, 0x60, 0x82,
    ).map { it.toByte() }.toByteArray()
}
