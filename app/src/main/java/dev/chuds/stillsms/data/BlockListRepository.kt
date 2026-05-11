package dev.chuds.stillsms.data

/*
 * BlockListRepository — plaintext JSON in filesDir/blocked.json.
 *
 * Format mirrors what still-dialer will use, deliberately, so the two apps can share a
 * single block list later without a migration:
 *
 *   {"blocked": ["+15551234567", "12345", "BANK-ID"]}
 *
 * On read we normalize to canonical exact-match keys so the UI can show the stored
 * sender key and the SMS_DELIVER drop-decision works as a plain set lookup.
 */

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BlockListRepository private constructor(
    private val file: File,
) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "blocked.json"))
    internal constructor(filesRoot: File, marker: Unit = Unit) : this(File(filesRoot, "blocked.json"))

    private val diskLock = lockFor(file)
    private val _state = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _state.asStateFlow()

    init {
        // Cheap: a few hundred bytes of JSON. Loaded once on construction, kept in memory.
        runBlocking(Dispatchers.IO) { loadFromDisk() }
    }

    suspend fun add(rawAddress: String): Boolean = withContext(Dispatchers.IO) {
        val n = BlockListMatcher.normalize(rawAddress) ?: return@withContext false
        synchronized(diskLock) {
            val updated = readFromDisk() + n
            _state.value = updated
            write(updated)
            true
        }
    }

    suspend fun remove(rawAddress: String) = withContext(Dispatchers.IO) {
        val n = BlockListMatcher.normalize(rawAddress) ?: return@withContext
        synchronized(diskLock) {
            val updated = readFromDisk() - n
            _state.value = updated
            write(updated)
        }
    }

    /** Hot-path check for SmsDeliverReceiver. */
    fun isBlocked(rawAddress: String?): Boolean {
        return BlockListMatcher.isBlocked(_state.value, rawAddress)
    }

    /** Exposed for screens that want a synchronously-readable snapshot. */
    fun snapshot(): Set<String> = _state.value

    private fun loadFromDisk() {
        synchronized(diskLock) {
            _state.value = readFromDisk()
        }
    }

    private fun readFromDisk(): Set<String> {
        if (!file.exists()) {
            return emptySet()
        }
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return@runCatching emptySet<String>()
            val arr = JSONObject(text).optJSONArray("blocked") ?: JSONArray()
            buildSet {
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i).orEmpty()
                    BlockListMatcher.normalize(raw)?.let { normalized -> this.add(normalized) }
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun write(values: Set<String>) {
        val arr = JSONArray()
        values.sorted().forEach { arr.put(it) }
        val obj = JSONObject().put("blocked", arr)
        file.writeText(obj.toString(2))
    }

    companion object {
        private val locks = mutableMapOf<String, Any>()

        @Synchronized
        private fun lockFor(file: File): Any {
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            return locks.getOrPut(key) { Any() }
        }
    }
}
