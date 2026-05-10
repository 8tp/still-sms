package dev.chuds.stillsms.data

/*
 * ThreadExporter — plaintext, cat-able, no clever encoding.
 *
 * Per SPEC §export: one .txt per thread, named `{display-name-or-number}.txt`, all
 * bundled into `still-sms-YYYY-MM-DD.zip` and dropped wherever the user picks via SAF.
 * Each line is `YYYY-MM-DD HH:MM  ->  body` for outbound, `<-` for inbound. Multi-line
 * bodies are flattened to single lines (newline → space) to keep the round-trip stable;
 * we don't try to preserve formatting that SMS itself doesn't preserve.
 */

import android.content.Context
import android.net.Uri
import dev.chuds.stillsms.data.Direction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThreadExporter(
    private val context: Context,
    private val threadRepository: ThreadRepository,
) {

    /** Export every thread to the given target URI as a zip. Returns (threadCount, messageCount). */
    suspend fun exportTo(targetUri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val threads = threadRepository.fetchThreads()
        val resolver = context.contentResolver
        var totalMessages = 0
        val outStream = resolver.openOutputStream(targetUri)
            ?: return@withContext ExportResult(0, 0, success = false)

        val seenNames = HashSet<String>()
        outStream.use { os ->
            ZipOutputStream(os).use { zip ->
                for (thread in threads) {
                    val msgs = threadRepository.fetchMessages(thread.id)
                    val raw = (thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address)
                    val baseName = sanitizeFilename(raw).ifBlank { "thread-${thread.id}" }
                    // Disambiguate when two threads collide on the same display name.
                    val name = uniquify(baseName, seenNames)
                    val content = formatThread(thread, msgs)
                    zip.putNextEntry(ZipEntry("$name.txt"))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    totalMessages += msgs.size
                }
            }
        }
        ExportResult(threads.size, totalMessages, success = true)
    }

    private fun formatThread(thread: Thread, messages: List<Message>): String {
        val sb = StringBuilder(messages.size * 80)
        sb.append("# ").append(thread.displayName ?: thread.address).append('\n')
        if (thread.displayName != null && thread.displayName != thread.address) {
            sb.append("# ").append(thread.address).append('\n')
        }
        sb.append('\n')
        for (m in messages) {
            sb.append(stamp(m.timestamp)).append("  ")
            sb.append(if (m.direction == Direction.Outbound) "->" else "<-").append("  ")
            // Single-line each message body. SMS doesn't carry meaningful newlines anyway.
            val body = if (m.isMms && m.body.isBlank() && m.attachmentUri != null) "[image]"
            else m.body.replace('\n', ' ').trim()
            sb.append(body).append('\n')
        }
        return sb.toString()
    }

    private fun stamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

    /** ASCII-conservative filename: keep letters, digits, +, -, _; collapse the rest to "_". */
    private fun sanitizeFilename(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.trim()) {
            sb.append(if (c.isLetterOrDigit() || c == '+' || c == '-' || c == '_') c else '_')
        }
        // Collapse runs of "_" and trim leading/trailing.
        return sb.toString().replace(Regex("_+"), "_").trim('_').take(80)
    }

    private fun uniquify(base: String, seen: MutableSet<String>): String {
        if (seen.add(base)) return base
        var i = 2
        while (true) {
            val candidate = "$base-$i"
            if (seen.add(candidate)) return candidate
            i++
        }
    }
}

data class ExportResult(val threadCount: Int, val messageCount: Int, val success: Boolean) {
    val isEmpty: Boolean get() = threadCount == 0
}
