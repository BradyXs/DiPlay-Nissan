package com.shilapi.xcertplay.network

import java.io.File
import java.io.IOException

/**
 * Optional kernel receive/link counters sampled by the wireless observer, never a media thread.
 * Android/OEM policy may hide these files; an inaccessible counter source is tried only once per run.
 * Protocol counters cover the device network namespace, not a particular interface or app stream.
 */
internal class WirelessReceiveDiagnostics(
    private val interfaceName: String?,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val interfaceSource = CounterSource("ifaceRx") {
        if (interfaceName == null || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
        linkedMapOf<String, Long>().apply {
            INTERFACE_RX_COUNTERS.forEach { (file, label) ->
                put(label, counter(read("/sys/class/net/$interfaceName/statistics/$file", 32).trim()))
            }
        }
    }
    private val interfaceTxSource = CounterSource("ifaceTx") {
        if (interfaceName == null || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
        linkedMapOf<String, Long>().apply {
            INTERFACE_TX_COUNTERS.forEach { (file, label) ->
                put(label, counter(read("/sys/class/net/$interfaceName/statistics/$file", 32).trim()))
            }
        }
    }
    private val udp4Source = CounterSource("udp4") {
        parseUdp4(read("/proc/net/snmp", MAX_PROC_BYTES))
    }
    private val udp6Source = CounterSource("udp6") {
        parseUdp6(read("/proc/net/snmp6", MAX_PROC_BYTES))
    }
    private val tcp4Source = CounterSource("tcp4") {
        parseTcp4(read("/proc/net/snmp", MAX_PROC_BYTES))
    }
    private var tcp6MissingCounters = "unknown"
    private val tcp6Source = CounterSource("tcp6") {
        parseTcp6(read("/proc/net/snmp6", MAX_PROC_BYTES)).also { counters ->
            tcp6MissingCounters = TCP_COUNTERS.filterNot(counters::containsKey)
                .takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none"
        }
    }
    private var previousSampleNs: Long? = null
    private var lastWindowMs = "baseline"

    fun snapshot(): String {
        val now = nowNs()
        val elapsed = previousSampleNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
        previousSampleNs = now
        lastWindowMs = elapsed?.toString() ?: "baseline"
        val prefix = "receiveCounters windowMs=$lastWindowMs udpScope=device "
        // Keep every source on its own short exported line, including full-width Long deltas.
        return listOf(interfaceSource, udp4Source, udp6Source).joinToString("\n") { prefix + it.snapshot() }
    }

    /** Adds optional link signals and TCP counters while retaining the original receive snapshot. */
    fun extendedSnapshot(): String {
        val receive = snapshot()
        val prefix = "wirelessCounters windowMs=$lastWindowMs scope=device "
        return listOf(
            receive,
            "wirelessCounters windowMs=$lastWindowMs scope=interface ${interfaceTxSource.snapshot()}",
            prefix + tcp4Source.snapshot(),
            prefix + tcp6Source.snapshot() + " missing=$tcp6MissingCounters",
            prefix + wirelessSignalSnapshot(),
        ).joinToString("\n")
    }

    private fun wirelessSignalSnapshot(): String {
        val name = interfaceName?.takeIf { INTERFACE_NAME.matches(it) }
            ?: return "procWireless=unavailable reason=interface_unknown"
        return try {
            val fields = parseProcWireless(read("/proc/net/wireless", MAX_PROC_BYTES), name)
                ?: return "procWireless=interface_missing"
            "procWireless=sampled status=${fields.getValue("status")} " +
                "link=${fields.getValue("link")} level=${fields.getValue("level")} " +
                "noise=${fields.getValue("noise")} nwid=${fields.getValue("nwid")} " +
                "crypt=${fields.getValue("crypt")} frag=${fields.getValue("frag")} " +
                "retries=${fields.getValue("retries")} misc=${fields.getValue("misc")} " +
                "beacon=${fields.getValue("beacon")} units=driver_reported"
        } catch (error: Exception) {
            "procWireless=unavailable failureClass=${error.javaClass.simpleName}"
        }
    }

    private class CounterSource(private val label: String, private val sample: () -> Map<String, Long>) {
        private var previous: Map<String, Long>? = null
        private var unavailable: String? = null

        fun snapshot(): String {
            unavailable?.let { return "$label=unavailable failureClass=$it" }
            val current = try { sample() } catch (error: Exception) {
                val type = error.javaClass.simpleName
                unavailable = type
                return "$label=unavailable failureClass=$type"
            }
            val before = previous
            previous = current
            if (before == null) return "$label=baseline"
            // Interface recreation or counter wrap must never appear as a negative loss count.
            if (current.keys != before.keys || current.any { (key, value) -> value < before.getValue(key) }) {
                return "$label=reset"
            }
            return "$label=sampled " + current.entries.joinToString(" ") { (key, value) ->
                "$label${key}Delta=${value - before.getValue(key)}"
            }
        }
    }

    companion object {
        private const val MAX_PROC_BYTES = 16 * 1024
        private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        private val INTERFACE_RX_COUNTERS = linkedMapOf(
            "rx_packets" to "Packets", "rx_bytes" to "Bytes", "rx_dropped" to "Dropped",
            "rx_errors" to "Errors", "rx_missed_errors" to "MissedErrors",
        )
        private val INTERFACE_TX_COUNTERS = linkedMapOf(
            "tx_packets" to "TxPackets", "tx_bytes" to "TxBytes", "tx_dropped" to "TxDropped",
            "tx_errors" to "TxErrors",
        )
        private val UDP_COUNTERS = listOf("InDatagrams", "InErrors", "RcvbufErrors", "InCsumErrors")
        private val TCP_COUNTERS = listOf("InSegs", "OutSegs", "RetransSegs", "InErrs", "OutRsts")
        private val OPTIONAL_TCP6_COUNTERS = setOf("RetransSegs", "InErrs", "OutRsts")

        internal fun parseUdp4(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val rows = text.lineSequence().filter { it.startsWith("Udp:") }.take(3).toList()
            if (rows.size != 2) throw IOException()
            val names = rows[0].trim().split(Regex("\\s+")).drop(1)
            val values = rows[1].trim().split(Regex("\\s+")).drop(1)
            if (names.size != values.size || names.distinct().size != names.size) throw IOException()
            return selectedUdpCounters(names.zip(values).toMap())
        }

        internal fun parseUdp6(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val values = linkedMapOf<String, String>()
            text.lineSequence().filter { it.startsWith("Udp6") }.forEach { row ->
                val parts = row.trim().split(Regex("\\s+"))
                if (parts.size != 2 || values.put(parts[0].removePrefix("Udp6"), parts[1]) != null) {
                    throw IOException()
                }
            }
            return selectedUdpCounters(values)
        }

        internal fun parseTcp4(text: String): Map<String, Long> = parseSnmpCounters(text, "Tcp:")

        internal fun parseTcp6(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val values = linkedMapOf<String, String>()
            text.lineSequence().filter { it.startsWith("Tcp6") }.forEach { row ->
                val parts = row.trim().split(Regex("\\s+"))
                if (parts.size != 2 || values.put(parts[0].removePrefix("Tcp6"), parts[1]) != null) {
                    throw IOException()
                }
            }
            return selectedTcpCounters(values, OPTIONAL_TCP6_COUNTERS)
        }

        internal fun parseProcWireless(text: String, interfaceName: String): Map<String, String>? {
            if (text.length > MAX_PROC_BYTES || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
            val line = text.lineSequence().firstOrNull {
                val colon = it.indexOf(':')
                colon >= 0 && it.substring(0, colon).trim() == interfaceName
            } ?: return null
            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (fields.size < 9) throw IOException()
            return linkedMapOf(
                "status" to fields[0].trimEnd('.', '=', '!', '+', ':'),
                "link" to fields[1].trimEnd('.', '=', '!', '+', ':'),
                "level" to fields[2].trimEnd('.', '=', '!', '+', ':'),
                "noise" to fields[3].trimEnd('.', '=', '!', '+', ':'),
                "nwid" to fields[4].trimEnd('.', '=', '!', '+', ':'),
                "crypt" to fields[5].trimEnd('.', '=', '!', '+', ':'),
                "frag" to fields[6].trimEnd('.', '=', '!', '+', ':'),
                "retries" to fields[7].trimEnd('.', '=', '!', '+', ':'),
                "misc" to fields[8].trimEnd('.', '=', '!', '+', ':'),
                "beacon" to fields.getOrElse(9) { "unknown" }.trimEnd('.', '=', '!', '+', ':'),
            )
        }

        private fun parseSnmpCounters(text: String, protocol: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val rows = text.lineSequence().filter { it.startsWith(protocol) }.take(2).toList()
            if (rows.size != 2) throw IOException()
            val names = rows[0].trim().split(Regex("\\s+")).drop(1)
            val values = rows[1].trim().split(Regex("\\s+")).drop(1)
            if (names.size != values.size || names.distinct().size != names.size) throw IOException()
            return selectedTcpCounters(names.zip(values).toMap())
        }

        private fun selectedUdpCounters(values: Map<String, String>): Map<String, Long> =
            linkedMapOf<String, Long>().apply {
                UDP_COUNTERS.forEach { key ->
                    val value = values[key]
                    // Older kernels may omit checksum-specific accounting.
                    if (value == null && key == "InCsumErrors") return@forEach
                    put(key, counter(value ?: throw IOException()))
                }
            }

        private fun selectedTcpCounters(
            values: Map<String, String>,
            optionalMissing: Set<String> = emptySet(),
        ): Map<String, Long> =
            linkedMapOf<String, Long>().apply {
                TCP_COUNTERS.forEach { key ->
                    val value = values[key]
                    if (value == null && key in optionalMissing) return@forEach
                    put(key, counter(value ?: throw IOException()))
                }
            }

        private fun counter(value: String): Long =
            value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException()
    }
}

/** Read only a small, fixed diagnostic file; never export its content or a read exception message. */
internal fun readBoundedDiagnosticFile(path: String, maxBytes: Int): String = File(path).inputStream().use { input ->
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) throw IOException()
        count += read
    }
    if (count > maxBytes) throw IOException()
    String(bytes, 0, count, Charsets.US_ASCII)
}
