package dev.chuds.stillsms.data

/**
 * Pure block-list matching helper.
 *
 * The stored and incoming forms are canonicalized before matching. E.164 addresses
 * stay as `+digits`, national/short-code senders become digits only, and alphanumeric
 * sender IDs become uppercase. Matching remains an exact set lookup, so "+15551234567"
 * never blocks "+155512345678". E.164 and bare-digit forms of the same number are
 * treated as equivalent so a stored "+15551234567" still matches an inbound
 * "15551234567" delivered without the leading "+", and vice versa.
 */
internal object BlockListMatcher {
    private val e164 = Regex("^\\+[1-9]\\d{1,14}$")
    private val numericSender = Regex("^\\d{2,15}$")
    private val alphaSender = Regex("^[A-Z0-9][A-Z0-9._-]{0,31}$")
    private val ignoredSeparators = setOf(' ', '-', '.', '(', ')')

    fun normalize(rawAddress: String?): String? {
        val raw = rawAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.any { it.isLetter() }) {
            val senderId = raw.uppercase()
            return senderId.takeIf { alphaSender.matches(it) }
        }

        val compact = StringBuilder(raw.length)
        for ((index, c) in raw.withIndex()) {
            when {
                c == '+' && index == 0 -> compact.append(c)
                c.isDigit() -> compact.append(c)
                c in ignoredSeparators -> Unit
                else -> return null
            }
        }
        val candidate = compact.toString()
        return candidate.takeIf { e164.matches(it) || numericSender.matches(it) }
    }

    fun isBlocked(blocked: Set<String>, rawAddress: String?): Boolean {
        val normalized = normalize(rawAddress) ?: return false
        if (blocked.contains(normalized)) return true
        val alternate = numericEquivalent(normalized) ?: return false
        return blocked.contains(alternate)
    }

    /**
     * Return the other canonical form of a phone-number-shaped key so that the E.164
     * "+digits" and bare-numeric forms of the same number resolve to a single match.
     * Returns null for alphanumeric senders or for forms that cannot be the same number.
     */
    private fun numericEquivalent(canonical: String): String? = when {
        canonical.startsWith("+") ->
            canonical.removePrefix("+").takeIf { numericSender.matches(it) }
        numericSender.matches(canonical) ->
            "+$canonical".takeIf { e164.matches(it) }
        else -> null
    }
}
