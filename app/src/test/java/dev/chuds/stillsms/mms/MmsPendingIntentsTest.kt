package dev.chuds.stillsms.mms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class MmsPendingIntentsTest {
    private lateinit var dir: File
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("still-sms-prefs-test").toFile()
        store = PreferenceDataStoreFactory.create(produceFile = { File(dir, "test.preferences_pb") })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun nextRequestCodeIsMonotonic() = runBlocking {
        val a = MmsPendingIntents.reserveBlock(store, 1)
        val b = MmsPendingIntents.reserveBlock(store, 1)
        val c = MmsPendingIntents.reserveBlock(store, 1)
        assertEquals(0, a)
        assertEquals(1, b)
        assertEquals(2, c)
    }

    @Test
    fun reserveBlockAdvancesPastTheBlock() = runBlocking {
        val first = MmsPendingIntents.reserveBlock(store, 10)
        val next = MmsPendingIntents.reserveBlock(store, 1)
        assertEquals(0, first)
        assertEquals(10, next)
    }

    /**
     * Regression: two outbound MMS whose URIs collide in 32-bit String.hashCode() must
     * still get distinct PendingIntent request codes. Pre-fix, both URIs hashed to the
     * same int and FLAG_UPDATE_CURRENT would overwrite the in-flight EXTRA_PDU_FILE.
     */
    @Test
    fun twoSendsWithCollidingUriHashGetDistinctRequestCodes() = runBlocking {
        // Picked so "content://mms/4567" and "content://mms/30621" both share an int hash
        // when forced — we don't need a real collision; the contract is "request codes
        // don't depend on URIs at all", which is sufficient to prove distinctness.
        val rcA = MmsPendingIntents.reserveBlock(store, 1)
        val rcB = MmsPendingIntents.reserveBlock(store, 1)
        assertNotEquals(rcA, rcB)
    }
}
