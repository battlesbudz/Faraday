package com.battlesbudz.jarvis.v2.privilege

/** A pairing code and optional wireless-debugging port read from one Settings window. */
internal data class PairingScreenReading(val code: String?, val port: Int?)

/** A code eligible for one pairing attempt. */
internal data class PairingAttempt(val code: String, val port: Int?)

/** Pure parsing and eligibility rules for the system pairing dialog. */
internal object PairingScreen {
    private val codePattern = Regex("""\d{6}""")
    private val ipv4PortPattern = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val ipv6PortPattern = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { text ->
            text.filterNot(Char::isWhitespace).takeIf(codePattern::matches)
        }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    fun attempt(
        reading: PairingScreenReading,
        pairingPortKnown: Boolean,
        waitingToPair: Boolean,
        tried: Set<String>,
    ): PairingAttempt? {
        val code = reading.code ?: return null
        if (code in tried || !(reading.port != null || pairingPortKnown) || !waitingToPair) return null
        return PairingAttempt(code, reading.port)
    }

    private fun ports(text: String): List<Int> {
        val ipv4 = ipv4PortPattern.findAll(text)
            .filter { match -> (1..4).all { match.groupValues[it].toInt() <= 255 } }
            .map { it.groupValues[5] }
        val ipv6 = ipv6PortPattern.findAll(text).map { it.groupValues[1] }
        return (ipv4 + ipv6).mapNotNull { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }.toList()
    }
}
