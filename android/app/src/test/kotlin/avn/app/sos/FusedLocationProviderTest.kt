package avn.app.sos

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.LocationFix
import avn.app.navigation.LocationSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WS21-05: "Fresh fix within 8 s else last-known with age; none gives 'location unavailable'". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FusedLocationProviderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val log = JsonLinesLogger(context)

    private class StubLocationSource(private val fix: LocationFix? = null, private val hang: Boolean = false) :
        LocationSource {
        override fun locations(): Flow<LocationFix> = emptyFlow()

        override suspend fun lastKnown(): LocationFix? {
            if (hang) awaitCancellation()
            return fix
        }
    }

    @Test
    fun aFreshFixHasZeroAgeAndAMapsLink() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, System.currentTimeMillis())
        val provider = FusedLocationProvider(StubLocationSource(fix), log)

        val result = provider.fetch()

        assertTrue(result is SosLocation.Fix)
        result as SosLocation.Fix
        assertEquals(30.0444, result.lat, 1e-9)
        assertEquals(31.2357, result.lon, 1e-9)
        assertTrue("expected age near 0ms, was ${result.ageMs}", result.ageMs in 0L..200L)
        assertEquals("https://maps.google.com/?q=30.044400,31.235700", result.mapsLink)
    }

    @Test
    fun aStaleCachedFixReportsItsRealAge() = runBlocking {
        val twoMinutesAgo = System.currentTimeMillis() - 120_000L
        val fix = LocationFix(30.0, 31.0, 20f, twoMinutesAgo)
        val provider = FusedLocationProvider(StubLocationSource(fix), log)

        val result = provider.fetch() as SosLocation.Fix

        assertTrue("expected age near 120000ms, was ${result.ageMs}", result.ageMs in 119_000L..121_000L)
    }

    @Test
    fun noFixAtAllIsUnavailable() = runBlocking {
        val provider = FusedLocationProvider(StubLocationSource(fix = null), log)

        assertEquals(SosLocation.Unavailable, provider.fetch())
    }

    @Test
    fun aHangingLookupTimesOutToUnavailable() = runBlocking {
        val provider = FusedLocationProvider(StubLocationSource(hang = true), log, timeoutMs = 50L)

        assertEquals(SosLocation.Unavailable, provider.fetch())
    }
}
