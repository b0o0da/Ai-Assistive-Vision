package avn.app.navigation

import android.annotation.SuppressLint
import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.NavState
import avn.engine.EngineInput
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine

/** Route data may use meters; hazards never do. */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Float, val timestampMs: Long)

interface LocationSource {
    fun locations(): Flow<LocationFix>

    suspend fun lastKnown(): LocationFix?
}

interface Navigation {
    val state: StateFlow<NavState>

    /** False when no route could be loaded. */
    suspend fun start(destination: String): Boolean

    fun cancel()
}

@Singleton
class FakeLocationSource @Inject constructor(private val log: JsonLinesLogger) : LocationSource {
    override fun locations(): Flow<LocationFix> = emptyFlow()

    override suspend fun lastKnown(): LocationFix? {
        log.log("location", "fake_last_known")
        return null
    }
}

@Singleton
class FakeNavigation @Inject constructor(private val log: JsonLinesLogger) : Navigation {
    override val state: StateFlow<NavState> = MutableStateFlow(NavState())

    override suspend fun start(destination: String): Boolean {
        log.log("navigation", "fake_start")
        return false
    }

    override fun cancel() = log.log("navigation", "fake_cancel")
}

/** Real [LocationSource]: Fused Location Provider. `locations()` is TD-004-adjacent future work --
 * nothing currently needs a continuous stream, so it stays empty rather than guessing an update
 * interval nobody asked for. */
@Singleton
class GpsLocationSource @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val log: JsonLinesLogger
) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    override fun locations(): Flow<LocationFix> = emptyFlow()

    @SuppressLint("MissingPermission") // caller (PermissionManager-gated UI) only invokes after grant
    override suspend fun lastKnown(): LocationFix? = suspendCancellableCoroutine { continuation ->
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                val fix = location?.let {
                    LocationFix(it.latitude, it.longitude, it.accuracy, System.currentTimeMillis())
                }
                log.log("location", "last_known", fields = mapOf("found" to (fix != null)))
                if (continuation.isActive) continuation.resume(fix)
            }
            .addOnFailureListener { e ->
                log.log("location", "last_known_failed", fields = mapOf("error" to e.javaClass.simpleName))
                if (continuation.isActive) continuation.resume(null)
            }
    }
}

/**
 * Real [Navigation]: resolves a destination through [RouteClient] (the frozen `GET /route`
 * contract) and tracks [NavState]. Turn-by-turn re-narration as the user walks (off-route
 * detection, step advancement) is navigation-to-engine integration -- architecture doc §"Mostafa
 * O. owns GPS/route logic; ... Marwan owns navigation-to-engine integration" -- so this only
 * submits the first instruction as a [Event] and leaves stepIndex/offRoute tracking for that
 * follow-on work rather than guessing an undiscussed polling design.
 */
@Singleton
class RealNavigation @Inject constructor(
    private val routeClient: RouteClient,
    private val locationSource: LocationSource,
    private val engineInput: EngineInput,
    private val log: JsonLinesLogger
) : Navigation {
    private val mutableState = MutableStateFlow(NavState())
    override val state: StateFlow<NavState> = mutableState.asStateFlow()

    @Volatile
    var lastRoute: RouteResult? = null
        private set

    override suspend fun start(destination: String): Boolean {
        val origin = locationSource.lastKnown()
        if (origin == null) {
            log.log("navigation", "start_no_location")
            return false
        }
        return try {
            val route = routeClient.getRoute(origin, destination)
            lastRoute = route
            mutableState.update { NavState(active = true, stepIndex = 0, offRoute = false) }
            route.steps.firstOrNull()?.let { firstStep ->
                engineInput.submit(
                    Event(
                        id = UUID.randomUUID().toString(),
                        source = Source.NAVIGATION,
                        type = EventType.NAV_INSTRUCTION,
                        label = firstStep.instruction,
                        side = null,
                        distanceBand = null,
                        confidence = 1.0,
                        risk = 0,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            log.log("navigation", "started", fields = mapOf("steps" to route.steps.size, "destination_name" to route.destinationName))
            true
        } catch (e: Exception) {
            log.log("navigation", "start_failed", fields = mapOf("error" to e.javaClass.simpleName))
            false
        }
    }

    override fun cancel() {
        lastRoute = null
        mutableState.update { NavState() }
        log.log("navigation", "cancelled")
    }
}
