package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** Whole-screen areas for the two fixed CarPlay dock positions. */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    initial: Int,
) {
    @Volatile var current: Int = initial
        private set

    fun withDock(dockEdge: Int): Int? = areas.indexOfFirst { it.dockEdge == dockEdge }
        .takeIf { it >= 0 }

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        fun build(width: Int, height: Int, dock: CarPlayDock): CarPlayViewAreas? {
            val initialEdge = dock.edge ?: return null
            val edges = listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM)
            val areas = edges.map { AirPlayViewArea(width, height, dockEdge = it) }
            return CarPlayViewAreas(areas, edges.indexOf(initialEdge).coerceAtLeast(0))
        }
    }
}
