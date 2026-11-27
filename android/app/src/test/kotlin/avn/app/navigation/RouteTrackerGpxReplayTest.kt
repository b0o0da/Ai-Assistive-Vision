package avn.app.navigation

import avn.contracts.LatLon
import avn.contracts.Maneuver
import avn.contracts.NavState
import avn.contracts.RouteResponse
import avn.contracts.RouteStep
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS20-01 (architecture.md WS-20 testing: "GPX replay unit tests for step progression and
 * off-route"). Replays the stored fixture (`tests/fixtures/routes/pharmacy_walk.gpx`, the same
 * three-step walk as `contracts/fixtures/api/route.ok.json`) through the real [RouteTracker],
 * end to end, instead of hand-typed fixes.
 */
class RouteTrackerGpxReplayTest {
    private val tracker = RouteTracker()

    private val fixes: List<LocationFix> by lazy {
        val dir = File(System.getProperty("avn.repoRoot"), "tests/fixtures/routes")
        GpxTrack.parse(File(dir, "pharmacy_walk.gpx").readText())
    }

    /** Step boundaries are this fixture's own waypoints: index 4 (step 0 end) and index 9 (arrive). */
    private fun routeFor(fixes: List<LocationFix>): RouteResponse {
        val step0End = LatLon(fixes[4].lat, fixes[4].lon)
        val step1End = LatLon(fixes[9].lat, fixes[9].lon)
        return RouteResponse(
            steps = listOf(
                RouteStep("Head north on the street", Maneuver.START, 120.0, step0End),
                RouteStep("Turn right", Maneuver.TURN_RIGHT, 80.0, step1End),
                RouteStep("The pharmacy is on your left", Maneuver.ARRIVE, 0.0, step1End)
            ),
            polyline = encodePolyline(fixes.map { LatLon(it.lat, it.lon) }),
            distanceM = 200.0,
            durationS = 160.0,
            destinationName = "Pharmacy (example)"
        )
    }

    @Test
    fun replayingEveryFixArrivesWithoutEverGoingOffRoute() {
        val route = routeFor(fixes)
        var state = NavState(active = true, stepIndex = 0, offRoute = false)

        for (fix in fixes) {
            state = tracker.advance(route, state, fix)
            assertFalse("went off-route at ${fix.lat},${fix.lon}", state.offRoute)
        }

        assertFalse("should have arrived (deactivated) by the last fix", state.active)
    }

    @Test
    fun aFixFarFromTheStoredRouteIsDetectedAsOffRoute() {
        val route = routeFor(fixes)
        var state = NavState(active = true, stepIndex = 0, offRoute = false)

        // Walk the first three real fixes, then take a wrong turn far from the path.
        for (fix in fixes.take(3)) {
            state = tracker.advance(route, state, fix)
        }
        val wrongTurn = fixes[3].copy(lon = fixes[3].lon + 0.01)

        state = tracker.advance(route, state, wrongTurn)

        assertTrue(state.offRoute)
    }

    companion object {
        /** Test-only inverse of [RouteTracker.decodePolyline] -- same helper as RouteTrackerTest. */
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
