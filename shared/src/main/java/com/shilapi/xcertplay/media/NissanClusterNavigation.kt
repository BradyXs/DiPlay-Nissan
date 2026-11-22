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

    fun format(route: CarPlayRouteSnapshot): Lines {
        val maneuver = route.nextManeuver
        val description = maneuver?.description?.trim().orEmpty()
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
        val detail = route.laneDescription?.takeIf { it.isNotBlank() }
            ?: maneuver?.afterRoadName?.takeIf { it.isNotBlank() }?.let { "进入$it" }
            ?: route.currentRoad?.takeIf { it.isNotBlank() }?.let { "沿$it" }
            ?: "导航中"
        return Lines(truncate(title, MAX_CHARS, MAX_BYTES), truncate(detail, MAX_CHARS, MAX_BYTES))
    }

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
        if (text.isNotEmpty()) return null
        // iAP2 maneuver types; intentionally do not reuse AMap Android icon numbers.
        return when (type) {
            1 -> "←左转"
            2 -> "→右转"
            3 -> "↑直行"
            4 -> "↓掉头"
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
    private const val MAX_CHARS = 14
    private const val MAX_BYTES = 57
}
