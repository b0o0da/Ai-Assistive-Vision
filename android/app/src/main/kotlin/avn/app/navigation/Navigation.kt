package avn.app.navigation

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.NavState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

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

/**
 * WS20-01: replays the stored GPX route (`$GPX_RESOURCE_PATH`, canonical copy at
 * `tests/fixtures/routes/pharmacy_walk.gpx`) instead of returning nothing, so navigation can be
 * developed and demoed at a desk with no GPS (architecture.md WS-20: "GPX replay lets it be
 * developed at a desk"). Loaded as a classpath resource -- same pattern as [DetectorConfig] --
 * so no [android.content.Context] is needed.
 */
@Singleton
class FakeLocationSource @Inject constructor(private val log: JsonLinesLogger) : LocationSource {
    private val fixes: List<LocationFix> by lazy {
        val text = FakeLocationSource::class.java.getResourceAsStream(GPX_RESOURCE_PATH)
            ?.bufferedReader()
            ?.use { it.readText() }
        text?.let { runCatching { GpxTrack.parse(it) }.getOrNull() }.orEmpty()
    }

    /** One fix every [REPLAY_INTERVAL_MS] -- the same cadence [FusedLocationSource] requests real updates at. */
    override fun locations(): Flow<LocationFix> = flow {
        if (fixes.isEmpty()) {
            log.log("location", "fake_gpx_empty")
            return@flow
        }
        for (fix in fixes) {
            emit(fix)
            delay(REPLAY_INTERVAL_MS)
        }
    }

    override suspend fun lastKnown(): LocationFix? {
        val fix = fixes.firstOrNull()
        log.log("location", "fake_last_known", fields = mapOf("loaded" to fixes.isNotEmpty()))
        return fix
    }

    companion object {
        private const val GPX_RESOURCE_PATH = "/routes/pharmacy_walk.gpx"
        private const val REPLAY_INTERVAL_MS = GpxTrack.DEFAULT_INTERVAL_MS
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
