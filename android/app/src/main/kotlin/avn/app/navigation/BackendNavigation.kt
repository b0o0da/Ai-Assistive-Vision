package avn.app.navigation

import avn.app.core.logging.JsonLinesLogger
import avn.app.data.api.ApiClient
import avn.app.data.api.ApiResult
import avn.app.data.api.Endpoint
import avn.contracts.NavState
import avn.contracts.RouteResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Real turn-by-turn start/cancel: `GET /route` through the existing backend proxy
 * (architecture.md WS-20) -- the phone holds no Maps key, the backend does. While active, a
 * background job tracks live fixes through [RouteTracker], announces each new step through
 * [TurnAnnouncer], pulses [HapticCues] on step change, and asks for one reroute the first time
 * [RouteTracker] reports the fix as off-route. If the reroute call itself fails, navigation keeps
 * going with the already loaded route rather than cancelling (architecture.md's offline rule).
 */
@Singleton
class BackendNavigation @Inject constructor(
    private val api: ApiClient,
    private val location: LocationSource,
    private val tracker: RouteTracker,
    private val announcer: TurnAnnouncer,
    private val haptics: HapticCues,
    private val scope: CoroutineScope,
    private val log: JsonLinesLogger
) : Navigation {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(NavState())
    override val state: StateFlow<NavState> = _state

    private var route: RouteResponse? = null
    private var destination: String? = null
    private var rerouted = false
    private var trackingJob: Job? = null

    override suspend fun start(destination: String): Boolean {
        // Always start from a clean slate: otherwise a failed start (no fix, fallback) after a
        // previous trip would leave _state.value reporting that trip's route as still active,
        // even though its tracking job was just cancelled.
        resetState()
        val origin = location.lastKnown()
        if (origin == null) {
            log.log("navigation", "start_failed_no_fix")
            return false
        }
        return when (val result = requestRoute(origin.lat, origin.lon, destination)) {
            is ApiResult.Success -> {
                val parsed = parseRoute(result.data)
                if (parsed == null) {
                    log.log("navigation", "route_unparseable")
                    false
                } else {
                    route = parsed
                    this.destination = destination
                    _state.value = NavState(active = true, stepIndex = 0, offRoute = false)
                    log.log("navigation", "started", fields = mapOf("steps" to parsed.steps.size))
                    announcer.announce(parsed.steps[0])
                    trackingJob = scope.launch {
                        location.locations().collect { fix -> onFix(fix) }
                    }
                    true
                }
            }
            is ApiResult.Fallback -> {
                log.log("navigation", "route_fallback", fields = mapOf("message" to result.message))
                false
            }
        }
    }

    override fun cancel() {
        resetState()
        log.log("navigation", "cancelled")
    }

    private fun resetState() {
        trackingJob?.cancel()
        trackingJob = null
        route = null
        destination = null
        rerouted = false
        _state.value = NavState()
    }

    private suspend fun onFix(fix: LocationFix) {
        val currentRoute = route ?: return
        val current = _state.value
        val next = tracker.advance(currentRoute, current, fix)
        if (next == current) return
        _state.value = next

        if (next.stepIndex != current.stepIndex) {
            haptics.pulse()
            if (next.active) {
                announcer.announce(currentRoute.steps[next.stepIndex])
            } else {
                log.log("navigation", "arrived")
                trackingJob?.cancel()
                trackingJob = null
            }
        } else if (next.offRoute && !current.offRoute && !rerouted) {
            rerouted = true
            haptics.pulse()
            reroute(fix)
        }
    }

    private suspend fun reroute(fix: LocationFix) {
        val target = destination ?: return
        when (val result = requestRoute(fix.lat, fix.lon, target)) {
            is ApiResult.Success -> {
                val parsed = parseRoute(result.data)
                if (parsed == null) {
                    log.log("navigation", "reroute_unparseable")
                } else {
                    route = parsed
                    _state.value = NavState(active = true, stepIndex = 0, offRoute = false)
                    log.log("navigation", "rerouted", fields = mapOf("steps" to parsed.steps.size))
                    announcer.announce(parsed.steps[0])
                }
            }
            is ApiResult.Fallback -> log.log(
                "navigation",
                "reroute_failed",
                fields = mapOf("message" to result.message)
            )
        }
    }

    private suspend fun requestRoute(lat: Double, lon: Double, destination: String): ApiResult {
        val body = buildJsonObject {
            put("origin", "$lat,$lon")
            put("destination", destination)
            put("mode", "walking")
        }
        return api.call(Endpoint.ROUTE, body)
    }

    private fun parseRoute(data: JsonElement): RouteResponse? {
        val parsed = runCatching { json.decodeFromJsonElement(RouteResponse.serializer(), data) }.getOrNull()
        return parsed?.takeIf { it.steps.isNotEmpty() }
    }
}
