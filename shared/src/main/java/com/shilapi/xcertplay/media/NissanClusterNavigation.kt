package com.shilapi.xcertplay.media

/** Holds the last navigation view briefly across transient inactive route frames. */
class NissanClusterRouteDisplay(private val exitDelayMillis: Long = 1_000L) {
    private var shown = CarPlayRouteSnapshot()
    private var exitAt: Long? = null

    fun update(route: CarPlayRouteSnapshot, nowMillis: Long) {
        if (route.active) {
            shown = route
            exitAt = null
        } else if (shown.active && exitAt == null) {
            exitAt = nowMillis + exitDelayMillis
        }
    }

    fun current(nowMillis: Long): CarPlayRouteSnapshot {
        val deadline = exitAt
        if (deadline != null && nowMillis >= deadline) {
            shown = CarPlayRouteSnapshot()
            exitAt = null
        }
        return shown
    }

    fun pendingDelay(nowMillis: Long): Long? = exitAt?.let { (it - nowMillis).coerceAtLeast(0L) }

    fun clear() {
        shown = CarPlayRouteSnapshot()
        exitAt = null
    }
}

/** Text for the P350 MCU's two media fields; no route or audio state is changed here. */
object NissanClusterNavigationFormatter {
    data class Lines(val title: String, val detail: String)

    fun format(route: CarPlayRouteSnapshot, vehicleSpeedKmh: Int? = null): Lines {
        val maneuver = route.nextManeuver
        val description = maneuver?.description?.let(::normalizeWhitespace).orEmpty()
        val direction = direction(description, maneuver?.type)
        // DistanceBetweenManeuver is from the previous maneuver, not the car's distance to it.
        val distance = route.distanceToNextManeuverMeters?.let(::distance).orEmpty()
        val action = direction ?: description.ifEmpty { null }
        val title = when {
            action != null && distance.isNotEmpty() -> {
                val suffix = " $distance"
                truncate(action, MAX_CHARS - suffix.codePointCount(0, suffix.length), MAX_BYTES - utf8Length(suffix)) + suffix
            }
            action != null -> action
            distance.isNotEmpty() -> "前方 $distance"
            else -> "导航中"
        }
        val afterRoad = maneuver?.afterRoadName?.let(::normalizeWhitespace)?.ifEmpty { null }
        val roadInInstruction = afterRoad?.let(::roadNameWithoutInstruction)?.takeIf { it.isNotEmpty() }
            ?.let { description.contains(it, ignoreCase = true) } == true
        val afterRoadDetail = afterRoad?.takeUnless { roadInInstruction }?.let { value ->
            if (hasInstructionPrefix(value)) value else "进入$value"
        }
        // The cluster's second media-text field is the only place for route context. Prefer
        // remaining distance/time when iAP2 provides them; afterRoadName often repeats the
        // maneuver instruction (e.g. "进入X"), which otherwise makes both rows say the same thing.
        val laneDetail = route.laneDescription?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() }
        val summary = routeSummary(route, vehicleSpeedKmh)
        val fallbackDetail = afterRoadDetail
            ?: route.currentRoad?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() }?.let { "沿$it" }
            ?: "导航中"
        val detail = if (vehicleSpeedKmh != null) {
            summary ?: laneDetail ?: fallbackDetail
        } else {
            laneDetail ?: summary ?: fallbackDetail
        }
        return Lines(truncate(title, MAX_CHARS, MAX_BYTES), truncate(detail, MAX_CHARS, MAX_BYTES))
    }

    private fun normalizeWhitespace(value: String): String = value.trim().replace(WHITESPACE, " ")

    private fun routeSummary(route: CarPlayRouteSnapshot, vehicleSpeedKmh: Int?): String? {
        val remainingDistance = route.remainingDistanceMeters?.let(::distance)?.takeIf { it.isNotEmpty() }
        val remainingTime = route.remainingTimeSeconds?.let(::duration)?.takeIf { it.isNotEmpty() }
        if (vehicleSpeedKmh != null) {
            return when {
                remainingDistance != null -> "${vehicleSpeedKmh}km/h 剩$remainingDistance"
                remainingTime != null -> "${vehicleSpeedKmh}km/h 预计$remainingTime"
                else -> "车速${vehicleSpeedKmh}km/h"
            }
        }
        return when {
            remainingDistance != null && remainingTime != null -> "剩余$remainingDistance · $remainingTime"
            remainingDistance != null -> "剩余$remainingDistance"
            remainingTime != null -> "预计$remainingTime"
            else -> null
        }
    }

    private fun duration(seconds: Long): String {
        if (seconds <= 0L) return ""
        if (seconds < 60L) return "${seconds}秒"
        val minutes = seconds / 60L + if (seconds % 60L >= 30L) 1L else 0L
        if (minutes < 60L) return "${minutes}分钟"
        val hours = minutes / 60L
        val remainder = minutes % 60L
        return if (remainder == 0L) "${hours}小时" else "${hours}小时${remainder}分"
    }

    private fun hasInstructionPrefix(value: String): Boolean =
        INSTRUCTION_PREFIXES.any { value.startsWith(it) }

    private fun roadNameWithoutInstruction(value: String): String =
        INSTRUCTION_PREFIXES.firstOrNull { value.startsWith(it) }
            ?.let { value.removePrefix(it).trimStart() }
            ?: value

    private fun direction(text: String, type: Int?): String? {
        // Prefer the instruction text. Never convert a negated instruction into a turn.
        if (text.contains("不要") || text.contains("禁止") || text.contains("无需")) return null
        val words = listOf(
            "掉头" to "↓掉头", "左转" to "←左转", "右转" to "→右转",
            "靠左" to "↖靠左", "左前方" to "↖靠左", "靠右" to "↗靠右", "右前方" to "↗靠右",
            "直行" to "↑直行", "到达目的地" to "到达目的地",
        )
        val match = words.mapNotNull { (word, label) -> text.indexOf(word).takeIf { it >= 0 }?.let { it to label } }
            .minByOrNull { it.first }
        if (match != null) return match.second
        // Instructions such as "进入某道路" often omit the action word, while iAP2 still
        // supplies a maneuver type. Use that protocol type as a fallback instead of dropping
        // the direction just because the street name is present.
        return when (type) {
            1 -> "←左转"
            2 -> "→右转"
            3 -> "↑直行"
            4 -> "↓掉头"
            50 -> "↗稍向右"
            else -> null
        }
    }

    private fun distance(meters: Long): String = when {
        meters < 0L || meters == 0xffff_ffffL -> ""
        meters < 1_000L -> "${meters}米"
        else -> {
            val tenths = (meters + 50L) / 100L
            if (tenths % 10L == 0L) "${tenths / 10L}km" else "${tenths / 10L}.${tenths % 10L}km"
        }
    }

    private fun truncate(value: String, maxChars: Int, maxBytes: Int): String {
        if (maxChars <= 0 || maxBytes <= 0) return ""
        val out = StringBuilder()
        var offset = 0
        var count = 0
        var bytes = 0
        while (offset < value.length && count < maxChars) {
            val point = value.codePointAt(offset)
            val part = String(Character.toChars(point))
            val size = utf8Length(part)
            if (bytes + size > maxBytes) break
            out.append(part)
            offset += Character.charCount(point)
            count++
            bytes += size
        }
        return out.toString()
    }

    private fun utf8Length(value: String) = value.toByteArray(Charsets.UTF_8).size
    private val WHITESPACE = Regex("\\s+")
    private val INSTRUCTION_PREFIXES = listOf("进入", "驶入", "继续沿", "沿", "去往", "前往")
    private const val MAX_CHARS = 14
    private const val MAX_BYTES = 57
}
