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
    fun normalize_rejectsNonE164AndEmbeddedNumbers() {
        assertNull(BlockListMatcher.normalize("15551234567"))
        assertNull(BlockListMatcher.normalize("from:+15551234567"))
        assertNull(BlockListMatcher.normalize("+15551234567;ext=8"))
        assertNull(BlockListMatcher.normalize("+0123456789"))
    }

    @Test
    fun isBlocked_usesExactCanonicalSetMatch() {
        val blocked = setOf("+15551234567")

        assertTrue(BlockListMatcher.isBlocked(blocked, "+1 (555) 123-4567"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "+155512345678"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "+1555123456"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "15551234567"))
        assertFalse(BlockListMatcher.isBlocked(blocked, "caller +15551234567"))
    }
}
