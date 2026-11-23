package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.LANE_GUIDANCE_UPDATE
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.ROUTE_GUIDANCE_MANEUVER_UPDATE
import com.shilapi.xcertplay.media.CarPlayRouteGuidance.Companion.ROUTE_GUIDANCE_UPDATE

/** Detailed route fields for the private diagnostic file. Never sends an iAP2 packet to the logger. */
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
            if (line.length + value.length + 1 > 650) {
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

    // Record each unknown field's size and possible scalar value for comparison across frames.
    private fun unknown(bytes: ByteArray): String = when (bytes.size) {
        1 -> "1B/u8:${Iap2WireCodec.readU8(bytes)}"
        2 -> "2B/u16:${Iap2WireCodec.readU16(bytes)}"
        4 -> "4B/u32:${Iap2WireCodec.readU32(bytes)}"
        8 -> "8B/u64:${Iap2WireCodec.readU64(bytes).toULong()}"
        else -> "${bytes.size}B"
    }
}
