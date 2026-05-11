package dev.chuds.stillsms.mms

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.chuds.stillsms.data.stillSmsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Issues monotonically-increasing PendingIntent request codes for outbound MMS, inbound
 * MMS download handoffs, and SMS multipart sends.
 *
 * Before this, request codes were derived from `uri.toString().hashCode()` (32-bit). With
 * FLAG_UPDATE_CURRENT | FLAG_MUTABLE, two messages whose URIs hash to the same value
 * silently overwrite each other's extras (EXTRA_PDU_FILE, EXTRA_DOWNLOAD_FILE,
 * EXTRA_FROM). Birthday-bound collision starts ~65k. For SMS multipart we additionally
 * had `hashCode() * 31 + i`, which collides at part index 31 inside a single message.
 *
 * Counter is persisted in DataStore so the keyspace is stable across process death; on
 * overflow we wrap to the reserved block size (no realistic risk in 2^31-wide space).
 */
internal object MmsPendingIntents {
    internal val COUNTER_KEY = intPreferencesKey("pending_intent_request_code")

    /** Reserve a single code (single-PDU send or single inbound download). */
    suspend fun nextRequestCode(context: Context): Int = reserveBlock(context, 1)

    /**
     * Reserves [size] consecutive codes and returns the first. Callers derive parts as
     * `base + i`. Used for SMS multipart so each part has its own slot AND the next
     * message's reservation starts past the block.
     */
    suspend fun reserveBlock(context: Context, size: Int): Int =
        reserveBlock(context.applicationContext.stillSmsDataStore, size)

    /** Blocking variant for non-suspending callers (SMS receive path). */
    fun nextRequestCodeBlocking(context: Context): Int = runBlocking { nextRequestCode(context) }

    /** Blocking variant for non-suspending callers (SMS multipart send path). */
    fun reserveBlockBlocking(context: Context, size: Int): Int =
        runBlocking { reserveBlock(context, size) }

    /** Test-only: read the current counter without bumping it. */
    internal fun peek(context: Context): Int = runBlocking {
        val prefs = context.applicationContext.stillSmsDataStore.data.first()
        prefs[COUNTER_KEY] ?: 0
    }

    /**
     * DataStore-injectable form, used by JVM tests. The Context-bound entry points are
     * thin wrappers around this.
     */
    internal suspend fun reserveBlock(store: DataStore<Preferences>, size: Int): Int {
        require(size > 0) { "reserveBlock size must be positive" }
        var base = 0
        store.edit { prefs ->
            val current = prefs[COUNTER_KEY] ?: 0
            base = current
            val next = current + size
            prefs[COUNTER_KEY] = if (next < 0) size else next
        }
        return base
    }
}
