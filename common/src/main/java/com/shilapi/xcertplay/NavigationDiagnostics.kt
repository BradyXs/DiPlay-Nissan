package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.LANE_GUIDANCE_UPDATE
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.ROUTE_GUIDANCE_MANEUVER_UPDATE
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.ROUTE_GUIDANCE_UPDATE
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Detailed route fields; never logs a complete iAP2 frame. */
internal object NavigationDiagnostics {
    private enum class Format { TEXT, U8, U16, U32, U64, CURSOR }
    private data class Field(val label: String, val format: Format)

    private val route = mapOf(
        1 to Field("state", Format.U8),
        3 to Field("road", Format.TEXT),
        4 to Field("destination", Format.TEXT),
        6 to Field("remainingSeconds", Format.U64),
        7 to Field("remainingMeters", Format.U32),
        10 to Field("nextMeters", Format.U32),
        13 to Field("maneuverCursor", Format.CURSOR),
        16 to Field("laneIndex", Format.U16),
        18 to Field("laneVisible", Format.U8),
        19 to Field("source", Format.TEXT),
    )
    private val maneuver = mapOf(
        1 to Field("index", Format.U16),
        2 to Field("instruction", Format.TEXT),
        3 to Field("type", Format.U8),
        4 to Field("afterRoad", Format.TEXT),
        5 to Field("segmentMeters", Format.U32),
    )
    private val lane = mapOf(
        1 to Field("index", Format.U16),
        3 to Field("lane", Format.TEXT),
    )

    fun received(sequence: Long, frame: Iap2Frame, emit: (String) -> Unit) {
        val schema = when (frame.messageId) {
            ROUTE_GUIDANCE_UPDATE -> route
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> maneuver
            LANE_GUIDANCE_UPDATE -> lane
            else -> return
        }
        val prefix = "NAV_RX seq=$sequence msg=0x${frame.messageId.toString(16)}"
        val parameters = runCatching { frame.body().asList() }.getOrElse {
            emit("$prefix malformed=true bytes=${frame.payload.size}")
            return
        }
        var line = "$prefix fields=${parameters.size}"
        parameters.take(64).forEach { parameter ->
            val field = schema[parameter.id]
            val bytes = parameter.payload
            val decoded = runCatching {
                field?.let { decode(it.format, bytes) } ?: unknown(bytes)
            }.getOrElse { "invalid(${bytes.size}B)" }
            val value = "${field?.label ?: "unknown"}[0x${parameter.id.toString(16)}]=$decoded"
            if (line.length + value.length + 1 > MAX_LOG_LINE_CHARS) {
                emit(line)
                line = "$prefix continued"
            }
            line += " $value"
        }
        if (parameters.size > 64) line += " omitted=${parameters.size - 64}"
        emit(line)
    }

    fun quote(text: String): String = DiagnosticRedactor.quoteNavigationText(text)

    private fun decode(format: Format, bytes: ByteArray): String {
        fun fixed(size: Int, read: () -> Any): String {
            require(bytes.size == size)
            return read().toString()
        }
        return when (format) {
            Format.TEXT -> quote(Iap2WireCodec.readString(bytes))
            Format.U8 -> fixed(1) { Iap2WireCodec.readU8(bytes) }
            Format.U16 -> fixed(2) { Iap2WireCodec.readU16(bytes) }
            Format.U32 -> fixed(4) { Iap2WireCodec.readU32(bytes) }
            Format.U64 -> fixed(8) { Iap2WireCodec.readU64(bytes).toULong() }
            Format.CURSOR -> {
                require(bytes.size == 2 || bytes.size == 4)
                Iap2WireCodec.readU16List(bytes).joinToString(",", "[", "]")
            }
        }
    }

    // Unknown schema fields retain a bounded raw-byte preview plus plausible interpretations.
    // Candidate values are deliberately labelled by endian/type and are not asserted as schema.
    private fun unknown(bytes: ByteArray): String = buildString {
        append(when (bytes.size) {
            1 -> "1B/u8:${Iap2WireCodec.readU8(bytes)}"
            2 -> "2B/u16:${Iap2WireCodec.readU16(bytes)}"
            4 -> "4B/u32:${Iap2WireCodec.readU32(bytes)}"
            8 -> "8B/u64:${Iap2WireCodec.readU64(bytes).toULong()}"
            else -> "${bytes.size}B"
        })
        val text = utf8Candidate(bytes)
        append(" raw=").append(if (text == null) rawPreview(bytes) else "<text-candidate>")
        val candidates = numericCandidates(bytes)
        if (candidates.isNotEmpty()) append(" candidates{").append(candidates.joinToString(",")).append('}')
        text?.let { append(" utf8?=").append(quote(it)) }
    }

    private fun rawPreview(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "<empty>"
        val shown = minOf(bytes.size, MAX_UNKNOWN_RAW_BYTES)
        val raw = buildString(shown * 3) {
            for (index in 0 until shown) {
                if (index > 0) append(' ')
                val value = bytes[index].toInt() and 0xff
                append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
            }
        }
        return if (shown < bytes.size) "$raw …(+${bytes.size - shown}B)" else raw
    }

    private fun numericCandidates(bytes: ByteArray): List<String> {
        if (bytes.size !in MIN_NUMERIC_GUESS_BYTES..MAX_NUMERIC_GUESS_BYTES) return emptyList()
        val littleEndian = bytes.reversedArray()
        val values = mutableListOf(
            "uBE=${BigInteger(1, bytes)}",
            "uLE=${BigInteger(1, littleEndian)}",
            "sBE=${BigInteger(bytes)}",
            "sLE=${BigInteger(littleEndian)}",
        )
        when (bytes.size) {
            4 -> {
                val big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getFloat()
                val little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat()
                if (!big.isNaN() && !big.isInfinite()) values += "f32BE=$big"
                if (!little.isNaN() && !little.isInfinite()) values += "f32LE=$little"
            }
            8 -> {
                val big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getDouble()
                val little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getDouble()
                if (!big.isNaN() && !big.isInfinite()) values += "f64BE=$big"
                if (!little.isNaN() && !little.isInfinite()) values += "f64LE=$little"
            }
        }
        return values
    }

    private fun utf8Candidate(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_UNKNOWN_TEXT_BYTES) return null
        var length = bytes.size
        while (length > 0 && bytes[length - 1] == 0.toByte()) length--
        if (length == 0) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString() }.getOrNull()
            ?: return null
        val printable = decoded.filterNot { it == '\u0000' }
        if (printable.isBlank() || printable.any { it.isISOControl() || it == '\uFFFD' }) return null
        return printable
    }

    private const val MAX_LOG_LINE_CHARS = 650
    private const val MAX_UNKNOWN_RAW_BYTES = 32
    private const val MAX_UNKNOWN_TEXT_BYTES = 80
    private const val MIN_NUMERIC_GUESS_BYTES = 2
    private const val MAX_NUMERIC_GUESS_BYTES = 8
    private const val HEX_DIGITS = "0123456789abcdef"
}
