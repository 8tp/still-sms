package dev.chuds.stillsms.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ThreadExportFormatter {
    fun formatThread(
        thread: Thread,
        messages: List<Message>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val sb = StringBuilder(messages.size * 80)
        sb.append("# ").append(thread.displayName ?: thread.address).append('\n')
        if (thread.displayName != null && thread.displayName != thread.address) {
            sb.append("# ").append(thread.address).append('\n')
        }
        sb.append('\n')
        for (message in messages) {
            sb.append(formatLine(message, timeZone)).append('\n')
        }
        return sb.toString()
    }

    fun formatLine(message: Message, timeZone: TimeZone = TimeZone.getDefault()): String {
        val arrow = if (message.direction == Direction.Outbound) "->" else "<-"
        val body = if (message.isMms && message.body.isBlank() && message.attachmentUri != null) {
            "[image]"
        } else {
            message.body.replace('\n', ' ').trim()
        }
        return "${stamp(message.timestamp, timeZone)}  $arrow  $body"
    }

    private fun stamp(epochMillis: Long, timeZone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(epochMillis))
}
