package avn.app.navigation

import avn.contracts.LatLon
import avn.contracts.Maneuver
import avn.contracts.NavState
import avn.contracts.RouteResponse
import avn.contracts.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrackerTest {
    private val tracker = RouteTracker()

    // Polyline vertices: origin, then each step's end in order (the arrive step reuses step 1's end).
    private val origin = LatLon(30.0400, 31.2357)
    private val step0End = LatLon(30.0500, 31.2357)
    private val step1End = LatLon(30.0500, 31.2450)

    private val route = RouteResponse(
        steps = listOf(
            RouteStep("Head north", Maneuver.START, 1_110.0, step0End),
            RouteStep("Turn right", Maneuver.TURN_RIGHT, 80.0, step1End),
            RouteStep("Arrive", Maneuver.ARRIVE, 0.0, step1End)
        ),
        polyline = encodePolyline(listOf(origin, step0End, step1End)),
        distanceM = 1_190.0,
        durationS = 120.0
    )

    private fun fixAt(lat: Double, lon: Double) = LocationFix(lat, lon, 5f, 1_000L)

    @Test
    fun aFixOnThePathNearTheStartOfTheFirstStepIsNotOffRoute() {
        val state = NavState(active = true, stepIndex = 0, offRoute = false)
        // ~11m from the route's origin, so ~1,100m from step 0's end -- distance-to-end alone
        // would wrongly call this off-route even though the fix sits right on the path.
        val fix = fixAt(30.0401, 31.2357)

        val next = tracker.advance(route, state, fix)

        assertEquals(state, next)
    }

    @Test
    fun aFixBetweenTheTwoRadiiOnThePathLeavesStateUnchanged() {
        val state = NavState(active = true, stepIndex = 0, offRoute = false)
        // ~33m from the step end, still on the origin -> step0End segment: past arrival (15m),
        // on the path so nowhere near the off-route radius (50m).
        val fix = fixAt(30.0497, 31.2357)

        val next = tracker.advance(route, state, fix)

        assertEquals(state, next)
    }

    @Test
    fun aFixWithinArrivalRadiusAdvancesToTheNextStep() {
        val state = NavState(active = true, stepIndex = 0, offRoute = false)
        val fix = fixAt(30.0500, 31.2357)

        val next = tracker.advance(route, state, fix)

        assertTrue(next.active)
        assertEquals(1, next.stepIndex)
        assertFalse(next.offRoute)
    }

    @Test
    fun arrivingAtTheFinalStepDeactivatesNavigation() {
        val state = NavState(active = true, stepIndex = 2, offRoute = false)
        val fix = fixAt(30.0500, 31.2450)

        val next = tracker.advance(route, state, fix)

        assertFalse(next.active)
        assertEquals(2, next.stepIndex)
    }

    @Test
    fun aFixBeyondOffRouteRadiusSetsOffRouteWithoutAdvancing() {
        val state = NavState(active = true, stepIndex = 0, offRoute = false)
        // North of step0's end, off both the origin->step0End and step0End->step1End segments.
        val fix = fixAt(30.0600, 31.2357)

        val next = tracker.advance(route, state, fix)

        assertEquals(0, next.stepIndex)
        assertTrue(next.offRoute)
    }

    @Test
    fun advanceIsANoOpWhenNavigationIsNotActive() {
        val state = NavState(active = false, stepIndex = 0, offRoute = false)
        val fix = fixAt(30.0500, 31.2357)

        val next = tracker.advance(route, state, fix)

        assertEquals(state, next)
    }

    companion object {
        /** Test-only inverse of [RouteTracker.decodePolyline], so fixtures can be built from plain lat/lon points. */
        private fun encodePolyline(points: List<LatLon>): String {
            val sb = StringBuilder()
            var prevLat = 0
            var prevLon = 0
            for (point in points) {
                val lat = Math.round(point.lat * 1e5).toInt()
                val lon = Math.round(point.lon * 1e5).toInt()
                sb.append(encodeValue(lat - prevLat))
                sb.append(encodeValue(lon - prevLon))
                prevLat = lat
                prevLon = lon
            }
            return sb.toString()
        }

        private fun encodeValue(value: Int): String {
            var v = value shl 1
            if (value < 0) v = v.inv()
            val sb = StringBuilder()
            while (v >= 0x20) {
                sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v shr 5
            }
            sb.append((v + 63).toChar())
            return sb.toString()
        }
    }
}
