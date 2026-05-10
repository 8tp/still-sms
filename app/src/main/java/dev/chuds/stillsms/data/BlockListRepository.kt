package dev.chuds.stillsms.data

/*
 * BlockListRepository — plaintext JSON in filesDir/blocked.json.
 *
 * Format mirrors what still-dialer will use, deliberately, so the two apps can share a
 * single block list later without a migration:
 *
 *   {"blocked": ["+15551234567", "+15552223333"]}
 *
 * On read we normalize to E.164 via PhoneNumberUtils.normalizeNumber so the UI can show
 * what the user typed but the SMS_DELIVER drop-decision works against canonical strings.
 */

import android.content.Context
import android.telephony.PhoneNumberUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BlockListRepository(context: Context) {

    private val file: File = File(context.applicationContext.filesDir, "blocked.json")
    private val _state = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _state.asStateFlow()

    init {
        // Cheap: a few hundred bytes of JSON. Loaded once on construction, kept in memory.
        runBlocking(Dispatchers.IO) { loadFromDisk() }
    }

    suspend fun add(rawAddress: String) = withContext(Dispatchers.IO) {
        val n = normalize(rawAddress) ?: return@withContext
        val updated = _state.value + n
        _state.value = updated
        write(updated)
    }

    suspend fun remove(rawAddress: String) = withContext(Dispatchers.IO) {
        val n = normalize(rawAddress) ?: return@withContext
        val updated = _state.value - n
        _state.value = updated
        write(updated)
    }

    /** Hot-path check for SmsDeliverReceiver. */
    fun isBlocked(rawAddress: String?): Boolean {
        val n = rawAddress?.let { normalize(it) } ?: return false
        return _state.value.contains(n)
    }

    /** Exposed for screens that want a synchronously-readable snapshot. */
    fun snapshot(): Set<String> = _state.value

    private fun loadFromDisk() {
        if (!file.exists()) {
            _state.value = emptySet()
            return
        }
        runCatching {
            val text = file.readText()
            if (text.isBlank()) return@runCatching emptySet<String>()
            val arr = JSONObject(text).optJSONArray("blocked") ?: JSONArray()
            buildSet {
                for (i in 0 until arr.length()) {
                    val raw = arr.optString(i).orEmpty()
                    normalize(raw)?.let { add(it) }
                }
            }
        }.onSuccess { set -> _state.value = set }
            .onFailure { _state.value = emptySet() }
    }

    private fun write(values: Set<String>) {
        val arr = JSONArray()
        values.sorted().forEach { arr.put(it) }
        val obj = JSONObject().put("blocked", arr)
        file.writeText(obj.toString(2))
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return PhoneNumberUtils.normalizeNumber(trimmed) ?: trimmed
    }
}
