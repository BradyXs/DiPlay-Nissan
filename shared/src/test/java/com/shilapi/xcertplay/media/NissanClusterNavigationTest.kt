package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanClusterNavigationTest {
    @Test fun routeExitDebounceKeepsNavigationUntilConfirmed() {
        val display = NissanClusterRouteDisplay(1_000)
        val navigation = CarPlayRouteSnapshot(active = true, currentRoad = "中山路")
        display.update(navigation, 100)
        display.update(CarPlayRouteSnapshot(), 200)
        assertTrue(display.current(500).active)
        display.update(navigation, 600)
        assertTrue(display.current(1_500).active)
        display.update(CarPlayRouteSnapshot(), 1_600)
        assertTrue(display.current(2_599).active)
        assertFalse(display.current(2_600).active)
    }

    @Test fun formatsShortDirectionDistanceAndNextRoad() {
        val route = CarPlayRouteSnapshot(
            active = true, currentRoad = "当前道路", distanceToNextManeuverMeters = 300,
            nextManeuver = CarPlayRouteManeuver(0, "前方右转", 2, "中山路", 900),
        )
        assertEquals(NissanClusterNavigationFormatter.Lines("→右转 300米", "进入中山路"),
            NissanClusterNavigationFormatter.format(route))
        assertEquals("进入中山路", NissanClusterNavigationFormatter.format(route.copy(laneDescription = null)).detail)
        assertEquals("直行车道", NissanClusterNavigationFormatter.format(route.copy(laneDescription = "直行车道")).detail)
    }

    @Test fun neverMistakesDistanceBetweenTurnsForDistanceFromTheCar() {
        val route = CarPlayRouteSnapshot(active = true,
            nextManeuver = CarPlayRouteManeuver(0, "左转", 1, null, 900))
        assertEquals("←左转", NissanClusterNavigationFormatter.format(route).title)
    }

    @Test fun unknownAndNegatedInstructionsDoNotInventATurn() {
        val route = CarPlayRouteSnapshot(active = true,
            nextManeuver = CarPlayRouteManeuver(0, "不要右转", 2, null, null))
        assertEquals("不要右转", NissanClusterNavigationFormatter.format(route).title)
        assertEquals("导航中", NissanClusterNavigationFormatter.format(route.copy(nextManeuver = null)).title)
    }

    @Test fun textIsBoundedWithoutSplittingUnicode() {
        val route = CarPlayRouteSnapshot(active = true, distanceToNextManeuverMeters = 1250,
            nextManeuver = CarPlayRouteManeuver(0, "右转", 2, "非常长的道路名称测试非常长的道路名称", null))
        val lines = NissanClusterNavigationFormatter.format(route)
        assertEquals("→右转 1.3km", lines.title)
        assertTrue(lines.detail.codePointCount(0, lines.detail.length) <= 14)
        assertTrue(lines.detail.toByteArray(Charsets.UTF_8).size <= 57)
    }
}
