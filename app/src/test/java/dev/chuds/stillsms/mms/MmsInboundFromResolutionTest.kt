package dev.chuds.stillsms.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression: carrier From != notification From keeps the placeholder in the
 * notification-time thread. Previously MmsDownloadReceiver re-bound the FROM addr row
 * on parsed.from, silently moving thread id mid-handshake.
 */
class MmsInboundFromResolutionTest {
    @Test
    fun keepsNotificationSeededSenderWhenCarrierFromDiverges() {
        // Notification seeded "+15551234567" into the placeholder. The carrier returns
        // an M-Retrieve.conf where the From header is "+15559999999". First writer wins.
        val resolved = resolveInboundFrom(
            seededFrom = "+15551234567",
            parsedFrom = "+15559999999",
            notifFrom = "+15551234567",
        )
        assertEquals("+15551234567", resolved)
    }

    @Test
    fun fallsBackToParsedFromWhenNothingSeeded() {
        val resolved = resolveInboundFrom(
            seededFrom = null,
            parsedFrom = "+15559999999",
            notifFrom = null,
        )
        assertEquals("+15559999999", resolved)
    }

    @Test
    fun fallsBackToNotifFromWhenParsedMissing() {
        val resolved = resolveInboundFrom(
            seededFrom = "",
            parsedFrom = null,
            notifFrom = "+15551234567",
        )
        assertEquals("+15551234567", resolved)
    }

    @Test
    fun returnsNullWhenAllSendersBlank() {
        assertNull(resolveInboundFrom(seededFrom = "", parsedFrom = null, notifFrom = ""))
    }
}
