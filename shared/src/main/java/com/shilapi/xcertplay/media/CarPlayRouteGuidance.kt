package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Structured route data retained from iAP2 Route Guidance updates. Unknown fields are ignored. */
data class CarPlayRouteSnapshot(
    val active: Boolean = false,
    val currentRoad: String? = null,
    val destination: String? = null,
    val remainingDistanceMeters: Long? = null,
    val remainingTimeSeconds: Long? = null,
    val distanceToNextManeuverMeters: Long? = null,
    val nextManeuverIndex: Int? = null,
    val sourceName: String? = null,
    val nextManeuver: CarPlayRouteManeuver? = null,
    val laneDescription: String? = null,
)

data class CarPlayRouteManeuver(
    val index: Int,
    val description: String?,
    val type: Int?,
    val afterRoadName: String?,
    val distanceMeters: Long?,
)

/**
 * Defensive parser for the CarPlay iAP2 route-guidance messages used by the Nissan cluster bridge.
 * Route payloads are incremental: absent values keep their previous state. This parser never
 * throws into the transport/control loop when a field is malformed or an extension is unknown.
 */
class CarPlayRouteGuidance {
    private var active = false
    private var currentRoad: String? = null
    private var destination: String? = null
    private var remainingDistanceMeters: Long? = null
    private var remainingTimeSeconds: Long? = null
    private var distanceToNextManeuverMeters: Long? = null
    private var nextManeuverIndex: Int? = null
    private var sourceName: String? = null
    private val maneuvers = LinkedHashMap<Int, CarPlayRouteManeuver>()
    private var laneIndex: Int? = null
    private var laneShowing = false
    private val laneDescriptions = LinkedHashMap<Int, String>()

    fun snapshot(): CarPlayRouteSnapshot = CarPlayRouteSnapshot(
        active = active,
        currentRoad = currentRoad,
        destination = destination,
        remainingDistanceMeters = remainingDistanceMeters,
        remainingTimeSeconds = remainingTimeSeconds,
        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
        nextManeuverIndex = nextManeuverIndex,
        sourceName = sourceName,
        nextManeuver = nextManeuverIndex?.let(maneuvers::get)
            ?: maneuvers.values.minByOrNullCompat { it.index },
        laneDescription = if (active && laneShowing) laneIndex?.let(laneDescriptions::get) else null,
    )

    /** Returns the changed retained snapshot, or null for a non-route or unchanged frame. */
    fun accept(frame: Iap2Frame): CarPlayRouteSnapshot? {
        if (frame.messageId != ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != LANE_GUIDANCE_UPDATE
        ) return null

        val before = snapshot()
        runCatching {
            when (frame.messageId) {
                ROUTE_GUIDANCE_UPDATE -> acceptState(Iap2BodyReader.of(frame))
                ROUTE_GUIDANCE_MANEUVER_UPDATE -> acceptManeuver(Iap2BodyReader.of(frame))
                LANE_GUIDANCE_UPDATE -> acceptLane(Iap2BodyReader.of(frame))
            }
        }
        val after = snapshot()
        return after.takeIf { it != before }
    }

    fun clear(): CarPlayRouteSnapshot? {
        val before = snapshot()
        active = false
        currentRoad = null
        destination = null
        remainingDistanceMeters = null
        remainingTimeSeconds = null
        distanceToNextManeuverMeters = null
        nextManeuverIndex = null
        sourceName = null
        maneuvers.clear()
        laneIndex = null
        laneShowing = false
        laneDescriptions.clear()
        return snapshot().takeIf { it != before }
    }

    private fun acceptState(body: Iap2BodyReader) {
        // Decode the complete update before mutating retained state, so one malformed field
        // cannot leave a partly applied route visible on the cluster.
        val state = body.optionalU8(ROUTE_STATE)
        if (state == INACTIVE_ROUTE_STATE) {
            clear()
            return
        }
        val road = body.optionalString(CURRENT_ROAD)
        val destinationName = body.optionalString(DESTINATION)
        val remainingDistance = body.optionalU32(DISTANCE_REMAINING)
        val remainingTime = body.optionalU64(TIME_REMAINING)
        val nextDistance = body.optionalU32(DISTANCE_TO_NEXT)
        val source = body.optionalString(SOURCE_NAME)
        val cursor = body.optionalBytes(MANEUVER_CURSOR)
        val activeLaneIndex = body.optionalU16(LANE_CURRENT_INDEX)
        val laneVisible = body.optionalU8(LANE_SHOWING)

        if (state != null) active = true
        road?.let { currentRoad = it.trim().ifEmpty { null } }
        destinationName?.let { destination = it.trim().ifEmpty { null } }
        remainingDistance?.let { remainingDistanceMeters = it }
        remainingTime?.let { remainingTimeSeconds = it }
        nextDistance?.let { distanceToNextManeuverMeters = it }
        source?.let { sourceName = it.trim().ifEmpty { null } }
        activeLaneIndex?.let { laneIndex = it }
        laneVisible?.let { laneShowing = it != 0 }

        // Captured iAP2 frames use 0x000d as a next-maneuver cursor: one u16 at route edges,
        // or (current, next) u16 values during normal guidance. Ignore unrecognized lengths.
        cursor?.let { bytes ->
            nextManeuverIndex = when (bytes.size) {
                2 -> readU16(bytes, 0)
                4 -> readU16(bytes, 2)
                else -> nextManeuverIndex
            }
        }
    }

    private fun acceptManeuver(body: Iap2BodyReader) {
        val index = body.optionalU16(MANEUVER_INDEX) ?: return
        if (index !in 0..512) return
        val maneuver = CarPlayRouteManeuver(
            index = index,
            description = body.optionalString(MANEUVER_DESCRIPTION)?.trim()?.ifEmpty { null },
            type = body.optionalU8(MANEUVER_TYPE),
            afterRoadName = body.optionalString(AFTER_ROAD_NAME)?.trim()?.ifEmpty { null },
            distanceMeters = body.optionalU32(DISTANCE_BETWEEN_MANEUVER),
        )
        if (index == 0 && maneuvers.isNotEmpty() && maneuvers[0] != maneuver) maneuvers.clear()
        maneuvers[index] = maneuver
        while (maneuvers.size > MAX_MANEUVER_COUNT) maneuvers.remove(maneuvers.keys.first())
        if (nextManeuverIndex == null) nextManeuverIndex = 0
        active = true
    }

    private fun acceptLane(body: Iap2BodyReader) {
        val index = body.optionalU16(LANE_INDEX) ?: return
        val text = body.optionalString(LANE_DESCRIPTION)?.trim() ?: return
        if (index !in 0..512) return
        if (text.isEmpty()) laneDescriptions.remove(index) else laneDescriptions[index] = text
        while (laneDescriptions.size > 32) laneDescriptions.remove(laneDescriptions.keys.first())
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun <T, R : Comparable<R>> Iterable<T>.minByOrNullCompat(selector: (T) -> R): T? {
        var best: T? = null
        var bestValue: R? = null
        for (item in this) {
            val value = selector(item)
            if (bestValue == null || value < bestValue) {
                best = item
                bestValue = value
            }
        }
        return best
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_UPDATE = 0x5204

        private const val ROUTE_STATE = 1
        private const val CURRENT_ROAD = 3
        private const val DESTINATION = 4
        private const val TIME_REMAINING = 6
        private const val DISTANCE_REMAINING = 7
        private const val DISTANCE_TO_NEXT = 10
        private const val MANEUVER_CURSOR = 13
        private const val SOURCE_NAME = 19
        private const val LANE_CURRENT_INDEX = 16
        private const val LANE_SHOWING = 18
        private const val LANE_INDEX = 1
        private const val LANE_DESCRIPTION = 3
        private const val MANEUVER_INDEX = 1
        private const val MANEUVER_DESCRIPTION = 2
        private const val MANEUVER_TYPE = 3
        private const val AFTER_ROAD_NAME = 4
        private const val DISTANCE_BETWEEN_MANEUVER = 5
        private const val INACTIVE_ROUTE_STATE = 0
        private const val MAX_MANEUVER_COUNT = 128
    }
}
