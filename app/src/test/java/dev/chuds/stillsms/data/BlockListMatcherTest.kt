package dev.chuds.stillsms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListMatcherTest {
    @Test
    fun normalize_acceptsStrictE164AndCommonSeparators() {
        assertEquals("+15551234567", BlockListMatcher.normalize("+15551234567"))
        assertEquals("+15551234567", BlockListMatcher.normalize("+1 (555) 123-4567"))
    }

    @Test
    fun normalize_acceptsShortCodesNationalNumbersAndSenderIds() {
        assertEquals("12345", BlockListMatcher.normalize("12345"))
        assertEquals("5551234567", BlockListMatcher.normalize("(555) 123-4567"))
        assertEquals("BANK-ID", BlockListMatcher.normalize("bank-id"))
        assertEquals("PAYPAL", BlockListMatcher.normalize("PayPal"))
    }

    @Test
    fun normalize_rejectsEmbeddedNumbersAndInvalidSenderIds() {
        assertNull(BlockListMatcher.normalize("from:+15551234567"))
        assertNull(BlockListMatcher.normalize("+15551234567;ext=8"))
        assertNull(BlockListMatcher.normalize("+0123456789"))
        assertNull(BlockListMatcher.normalize("sender id"))
        assertNull(BlockListMatcher.normalize("from:bank"))
    }

    @Test
    fun isBlocked_usesExactCanonicalSetMatch() {
        val blocked = setOf("+15551234567", "12345", "BANK-ID")

        assertTrue(BlockListMatcher.isBlocked(blocked, "+1 (555) 123-4567"))
        assertTrue(BlockListMatcher.isBlocked(blocked, "12345"))
        assertTrue(BlockListMatcher.isBlocked(blocked, "bank-id"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "+155512345678"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "+1555123456"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "123456"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "BANK-ID2"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "caller +15551234567"))
    }
}
