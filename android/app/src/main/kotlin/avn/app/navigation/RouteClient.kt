package avn.app.navigation

/**
 * One step of a walking route, mirroring the `data.steps[]` shape of the frozen `GET /route`
 * response (docs/api-contract.md, contracts/fixtures/api/route.ok.json). Distance stays in
 * meters here -- route data is allowed to use meters, hazards never are (see android/CLAUDE.md
 * Safety rules).
 */
data class RouteStep(val instruction: String, val maneuver: String, val distanceMeters: Double)

data class RouteResult(
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val destinationName: String?
)

/** Thrown when the backend envelope reports `ok:false`, or when fallback has no cached route. */
class RouteRequestException(message: String) : Exception(message)

/**
 * Resolves a destination query (free text or "lat,lon") into a walking route by calling the
 * already-frozen `GET /route` endpoint (docs/api-contract.md). Not a fake/real DI seam by
 * itself -- it's a dependency *of* the real [Navigation] implementation, the same way
 * `AvnDatabase` is a dependency of `DataModule`'s DAOs.
 */
interface RouteClient {
    /** @param destinationQuery free text (e.g. an OCR-read sign) or "lat,lon". */
    suspend fun getRoute(origin: LocationFix, destinationQuery: String): RouteResult
}
