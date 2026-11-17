package avn.app.navigation

import avn.contracts.LatLon
import avn.contracts.NavState
import avn.contracts.RouteResponse
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Step progression (arrival at the current step's end) and off-route detection against the
 * route's own geometry: perpendicular distance to the nearest segment of the decoded `polyline`,
 * not distance to the current step's end point. A fix near the *start* of a long step is on the
 * path even though it is far from that step's end, so distance-to-end alone would misreport it as
 * off-route.
 */
class RouteTracker @Inject constructor() {
    fun advance(route: RouteResponse, state: NavState, fix: LocationFix): NavState {
        if (!state.active) return state
        val step = route.steps.getOrNull(state.stepIndex) ?: return state
        val distanceToEnd = distanceMeters(fix.lat, fix.lon, step.end.lat, step.end.lon)
        if (distanceToEnd <= ARRIVAL_RADIUS_M) {
            val nextIndex = state.stepIndex + 1
            return if (nextIndex >= route.steps.size) {
                NavState(active = false, stepIndex = state.stepIndex, offRoute = false)
            } else {
                NavState(active = true, stepIndex = nextIndex, offRoute = false)
            }
        }
        val distanceToPath = distanceToPathMeters(fix, decodePolyline(route.polyline))
        return state.copy(offRoute = distanceToPath > OFF_ROUTE_RADIUS_M)
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 15.0
        const val OFF_ROUTE_RADIUS_M = 50.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        fun distanceMeters(fix: LocationFix, to: LatLon): Double = distanceMeters(fix.lat, fix.lon, to.lat, to.lon)

        /** Shortest distance from [fix] to the polyline as a chain of segments, not to any single point. */
        fun distanceToPathMeters(fix: LocationFix, path: List<LatLon>): Double {
            if (path.isEmpty()) return Double.MAX_VALUE
            if (path.size == 1) return distanceMeters(fix.lat, fix.lon, path[0].lat, path[0].lon)
            return path.zipWithNext().minOf { (a, b) -> distanceToSegmentMeters(fix, a, b) }
        }

        /** Point-to-segment distance via an equirectangular projection local to [a]; accurate at this scale. */
        private fun distanceToSegmentMeters(fix: LocationFix, a: LatLon, b: LatLon): Double {
            val metersPerDegLat = EARTH_RADIUS_M * Math.PI / 180.0
            val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(a.lat))

            val bx = (b.lon - a.lon) * metersPerDegLon
            val by = (b.lat - a.lat) * metersPerDegLat
            val px = (fix.lon - a.lon) * metersPerDegLon
            val py = (fix.lat - a.lat) * metersPerDegLat

            val lengthSquared = bx * bx + by * by
            val t = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            return sqrt(dx * dx + dy * dy)
        }

        /** Google's encoded polyline algorithm format (the one `polyline` fields use throughout the contract). */
        fun decodePolyline(encoded: String): List<LatLon> {
            val points = mutableListOf<LatLon>()
            var index = 0
            var lat = 0
            var lon = 0
            while (index < encoded.length) {
                var shift = 0
                var result = 0
                var byte: Int
                do {
                    byte = encoded[index++].code - 63
                    result = result or ((byte and 0x1f) shl shift)
                    shift += 5
                } while (byte >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

                shift = 0
                result = 0
                do {
                    byte = encoded[index++].code - 63
                    result = result or ((byte and 0x1f) shl shift)
                    shift += 5
                } while (byte >= 0x20)
                lon += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

                points += LatLon(lat / 1e5, lon / 1e5)
            }
            return points
        }
    }
}
