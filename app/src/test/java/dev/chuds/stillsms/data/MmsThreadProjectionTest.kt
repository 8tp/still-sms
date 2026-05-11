package dev.chuds.stillsms.data

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsThreadProjectionTest {
    @Test
    fun failedInboundMmsWithFromAddressStaysInbound() {
        assertEquals(
            Direction.Inbound,
            mmsDirectionFor(
                msgBox = Telephony.Mms.MESSAGE_BOX_FAILED,
                fromAddress = "+15551234567",
            ),
        )
    }

    @Test
    fun failedMmsWithoutFromAddressStaysOutbound() {
        assertEquals(
            Direction.Outbound,
            mmsDirectionFor(
                msgBox = Telephony.Mms.MESSAGE_BOX_FAILED,
                fromAddress = null,
            ),
        )
    }

    @Test
    fun emptyFailedMmsGetsVisibleFailureBody() {
        assertEquals(
            "[mms download failed]",
            mmsFallbackBody(
                msgBox = Telephony.Mms.MESSAGE_BOX_FAILED,
                failed = true,
                attachmentUri = null,
            ),
        )
    }

    @Test
    fun autoDownloadOffPlaceholderGetsVisiblePendingBody() {
        assertEquals(
            "[mms not downloaded]",
            mmsFallbackBody(
                msgBox = Telephony.Mms.MESSAGE_BOX_INBOX,
                failed = false,
                attachmentUri = null,
            ),
        )
    }
}
