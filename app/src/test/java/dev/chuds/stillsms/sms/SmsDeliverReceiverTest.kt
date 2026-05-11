package dev.chuds.stillsms.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDeliverReceiverTest {
    @Test
    fun notificationRequiresSuccessfulProviderInsert() {
        assertFalse(shouldNotifyInboundSms(insertSucceeded = false, threadId = 1L, body = "hello"))
        assertFalse(shouldNotifyInboundSms(insertSucceeded = true, threadId = -1L, body = "hello"))
        assertFalse(shouldNotifyInboundSms(insertSucceeded = true, threadId = 1L, body = ""))

        assertTrue(shouldNotifyInboundSms(insertSucceeded = true, threadId = 1L, body = "hello"))
    }
}
