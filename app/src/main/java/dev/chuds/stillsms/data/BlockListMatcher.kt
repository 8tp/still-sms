package dev.chuds.stillsms.data

/**
 * Pure block-list matching helper.
 *
 * The stored and incoming forms are canonicalized to strict E.164 before matching.
 * That keeps the hot-path check as an exact set lookup and avoids substring matches
 * such as "+15551234567" accidentally blocking "+155512345678".
 */
internal object BlockListMatcher {
    private val e164 = Regex("^\\+[1-9]\\d{1,14}$")
    private val ignoredSeparators = setOf(' ', '-', '.', '(', ')')

    fun normalize(rawAddress: String?): String? {
        val raw = rawAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val compact = StringBuilder(raw.length)
        for ((index, c) in raw.withIndex()) {
            when {
                c == '+' && index == 0 -> compact.append(c)
                c.isDigit() -> compact.append(c)
                c in ignoredSeparators -> Unit
                else -> return null
            }
        }
        return compact.toString().takeIf { e164.matches(it) }
    }

    fun isBlocked(blocked: Set<String>, rawAddress: String?): Boolean {
        val normalized = normalize(rawAddress) ?: return false
        return blocked.contains(normalized)
    }
}
