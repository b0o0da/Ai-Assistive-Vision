package avn.app.sos

import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.LocationFix
import avn.app.navigation.LocationSource
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One best-effort location for an SOS message (architecture.md WS-21: "get the best location
 * (fresh fix with timeout, else last known with its age)"). [ageMs] is 0 for a fresh fix and
 * grows for an older cached one -- the demo script shows the SMS as "a maps link and the fix age".
 */
sealed interface SosLocation {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val ageMs: Long, val mapsLink: String) :
        SosLocation

    data object Unavailable : SosLocation
}

/** Spoken-safe fallback when no fix -- fresh or cached -- could be obtained in time. */
const val SOS_LOCATION_UNAVAILABLE_MESSAGE = "I can't get a location right now."

interface SosLocationFixer {
    suspend fun fetch(): SosLocation
}

/**
 * WS21-05: wraps the existing [LocationSource] seam (real GPS or the WS20-01 GPX replay fake,
 * depending on `location` in [avn.app.BuildConfig.REAL_SEAMS]) with the bound this feature needs
 * -- the whole fresh-or-cached lookup must resolve within [timeoutMs], never hang the SOS
 * countdown. [LocationSource.lastKnown] already returns null on a denied permission the same way
 * it does for a true no-fix, so that case reaches [SosLocation.Unavailable] too, with no separate
 * permission check needed here.
 */
@Singleton
class FusedLocationProvider @Inject constructor(
    private val location: LocationSource,
    private val log: JsonLinesLogger
) : SosLocationFixer {
    /** Test-only: a short timeout instead of waiting [DEFAULT_TIMEOUT_MS] for real. */
    constructor(location: LocationSource, log: JsonLinesLogger, timeoutMs: Long) : this(location, log) {
        this.timeoutMs = timeoutMs
    }

    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    override suspend fun fetch(): SosLocation {
        val requestedAt = System.currentTimeMillis()
        val fix = withTimeoutOrNull(timeoutMs) { location.lastKnown() }
        if (fix == null) {
            log.log("sos", "location_unavailable")
            return SosLocation.Unavailable
        }
        val ageMs = (requestedAt - fix.timestampMs).coerceAtLeast(0)
        log.log("sos", "location_fixed", fields = mapOf("age_ms" to ageMs))
        return SosLocation.Fix(fix.lat, fix.lon, fix.accuracyM, ageMs, mapsLink(fix))
    }

    private fun mapsLink(fix: LocationFix): String {
        val lat = String.format(Locale.US, "%.6f", fix.lat)
        val lon = String.format(Locale.US, "%.6f", fix.lon)
        return "https://maps.google.com/?q=$lat,$lon"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
