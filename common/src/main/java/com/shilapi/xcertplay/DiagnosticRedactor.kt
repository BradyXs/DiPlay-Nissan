package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|(?:[a-z0-9_-]*ssid)\\s*[=:]|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    /** Quotes a typed navigation string while keeping the general-purpose log filters in force. */
    fun quoteNavigationText(text: String): String {
        val value = buildString {
            for (ch in text.take(160)) {
                if (length > 210) {
                    append('…')
                    break
                }
                when {
                    ch == '"' || ch == '\\' -> append('\\').append(ch)
                    ch.isISOControl() || ch == '\u2028' || ch == '\u2029' -> append(escapeCharacter(ch))
                    else -> append(ch)
                }
            }
            if (text.length > 160 && lastOrNull() != '…') append('…')
        }
        val withoutKeywords = secret.replace(value) { escapeKeyword(it.value) }
        val escaped = namedDevice.replace(withoutKeywords) { escapeKeyword(it.value) }
            .replace("TRACE ", "TRACE\\u0020").replace("PHONE ", "PHONE\\u0020")
        return "\"${if (escaped.length <= 320) escaped else escaped.take(320) + '…'}\""
    }

    private fun escapeKeyword(match: String): String = escapeCharacter(match.first()) + match.drop(1)

    private fun escapeCharacter(ch: Char): String = "\\u" + ch.code.toString(16).padStart(4, '0')

    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(700)
    }
}
