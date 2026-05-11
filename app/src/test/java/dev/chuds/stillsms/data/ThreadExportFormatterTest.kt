package dev.chuds.stillsms.data

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadExportFormatterTest {
    @Test
    fun formatThread_writesPlaintextHeadersAndStableLines() {
        val thread = Thread(
            id = 9L,
            address = "+15551234567",
            displayName = "Ada",
            photoUri = null,
            snippet = "",
            timestamp = 0L,
            read = true,
            messageCount = 2,
        )
        val messages = listOf(
            Message(
                id = 1L,
                threadId = 9L,
                address = "+15551234567",
                body = "hello\nthere",
                timestamp = 0L,
                direction = Direction.Outbound,
                read = true,
                isMms = false,
            ),
            Message(
                id = 2L,
                threadId = 9L,
                address = "+15551234567",
                body = "",
                timestamp = 60_000L,
                direction = Direction.Inbound,
                read = true,
                isMms = true,
                attachmentUri = "content://mms/part/2",
            ),
        )

        val formatted = ThreadExportFormatter.formatThread(
            thread = thread,
            messages = messages,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertEquals(
            """
            # Ada
            # +15551234567

            1970-01-01 00:00  ->  hello there
            1970-01-01 00:01  <-  [image]

            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun formatThread_omitsDuplicateAddressHeader() {
        val thread = Thread(
            id = 4L,
            address = "+15557654321",
            displayName = "+15557654321",
            photoUri = null,
            snippet = "",
            timestamp = 0L,
            read = true,
            messageCount = 0,
        )

        assertEquals(
            "# +15557654321\n\n",
            ThreadExportFormatter.formatThread(thread, emptyList(), TimeZone.getTimeZone("UTC")),
        )
    }
}
