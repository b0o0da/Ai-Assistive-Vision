package avn.app.navigation

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import avn.app.data.api.ApiClient
import avn.app.data.api.ApiResult
import avn.app.data.api.Endpoint
import avn.contracts.Event
import avn.contracts.NavState
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WS-20: [BackendNavigation.start]/live tracking against the frozen `route.ok.json` fixture.
 * Runs through Robolectric only because [JsonLinesLogger] needs a real [Context]; [ApiClient] and
 * [LocationSource] are faked directly, no network and no GPS involved. The tracking job runs on
 * an Unconfined [CoroutineScope] so a fed fix is fully processed by the time `fixes.tryEmit`
 * returns, keeping the tests synchronous.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendNavigationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/api")
    private val routeData = Json.parseToJsonElement(File(fixtures, "route.ok.json").readText()).jsonObject["data"]!!

    // route.ok.json step ends, used to drive live fixes through RouteTracker.
    private val step0End = LocationFix(30.0447, 31.2359, 5f, 2_000L)
    private val step1And2End = LocationFix(30.0452, 31.2368, 5f, 3_000L)
    private val farAway = LocationFix(30.0300, 31.2200, 5f, 4_000L)

    private class FakeLocation(var fix: LocationFix?) : LocationSource {
        val fixes = kotlinx.coroutines.flow.MutableSharedFlow<LocationFix>(extraBufferCapacity = 8)
        override fun locations() = fixes
        override suspend fun lastKnown(): LocationFix? = fix
    }

    /** Returns [results] in order, repeating the last one for any call beyond its size. */
    private class FakeApi(private val results: List<ApiResult>) : ApiClient {
        val bodies = mutableListOf<JsonObject?>()
        val lastBody: JsonObject? get() = bodies.lastOrNull()

        override suspend fun call(endpoint: Endpoint, body: JsonObject?): ApiResult {
            assertEquals(Endpoint.ROUTE, endpoint)
            bodies += body
            return results.getOrElse(bodies.size - 1) { results.last() }
        }
    }

    private class RecordingEngineInput : EngineInput {
        val submitted = mutableListOf<SpeechCandidate>()
        override fun submit(event: Event) = throw UnsupportedOperationException("unused by BackendNavigation")
        override fun submit(candidate: SpeechCandidate) {
            submitted += candidate
        }
    }

    private class CountingHaptics : HapticCues {
        var pulses = 0
        override fun pulse() {
            pulses++
        }
    }

    private fun navigation(
        api: ApiClient,
        location: LocationSource,
        engineInput: EngineInput = RecordingEngineInput(),
        haptics: HapticCues = CountingHaptics()
    ) = BackendNavigation(
        api,
        location,
        RouteTracker(),
        TurnAnnouncer(engineInput),
        haptics,
        CoroutineScope(Dispatchers.Unconfined),
        JsonLinesLogger(context)
    )

    @Test
    fun aSuccessfulRouteActivatesNavigationAtStepZero() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_1")))
        val nav = navigation(api, FakeLocation(fix))

        val started = nav.start("nearest pharmacy")

        assertTrue(started)
        assertTrue(nav.state.value.active)
        assertEquals(0, nav.state.value.stepIndex)
        assertFalse(nav.state.value.offRoute)
        assertEquals("30.0444,31.2357", api.lastBody!!["origin"]!!.jsonPrimitive.content)
        assertEquals("nearest pharmacy", api.lastBody!!["destination"]!!.jsonPrimitive.content)
        assertEquals("walking", api.lastBody!!["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun noLocationFixFailsWithoutCallingTheBackend() = runBlocking {
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_2")))
        val nav = navigation(api, FakeLocation(null))

        val started = nav.start("nearest pharmacy")

        assertFalse(started)
        assertFalse(nav.state.value.active)
        assertNull(api.lastBody)
    }

    @Test
    fun aBackendFallbackLeavesNavigationInactive() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Fallback("I can't get directions right now.", null, "req_3")))
        val nav = navigation(api, FakeLocation(fix))

        val started = nav.start("nearest pharmacy")

        assertFalse(started)
        assertFalse(nav.state.value.active)
    }

    @Test
    fun cancelResetsAnActiveRouteBackToTheDefaultState() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_4")))
        val nav = navigation(api, FakeLocation(fix))
        nav.start("nearest pharmacy")
        assertTrue(nav.state.value.active)

        nav.cancel()

        assertEquals(NavState(), nav.state.value)
    }

    @Test
    fun aLiveFixWithinArrivalRadiusAdvancesTheStepAndAnnouncesAndPulses() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_5")))
        val location = FakeLocation(fix)
        val engineInput = RecordingEngineInput()
        val haptics = CountingHaptics()
        val nav = navigation(api, location, engineInput, haptics)
        nav.start("nearest pharmacy")
        assertEquals(1, engineInput.submitted.size)

        location.fixes.tryEmit(step0End)

        assertEquals(1, nav.state.value.stepIndex)
        assertFalse(nav.state.value.offRoute)
        assertEquals(1, haptics.pulses)
        assertEquals(2, engineInput.submitted.size)
        assertEquals("Turn right", engineInput.submitted[1].text)
    }

    @Test
    fun threeAdvancingFixesReachTheFinalStepAndDeactivateNavigation() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_6")))
        val location = FakeLocation(fix)
        val nav = navigation(api, location)
        nav.start("nearest pharmacy")

        location.fixes.tryEmit(step0End)
        assertEquals(1, nav.state.value.stepIndex)
        location.fixes.tryEmit(step1And2End)
        assertEquals(2, nav.state.value.stepIndex)
        location.fixes.tryEmit(step1And2End)

        assertFalse(nav.state.value.active)
        assertEquals(2, nav.state.value.stepIndex)
    }

    @Test
    fun goingOffRouteTriggersExactlyOneRerouteAndOnePulsePerSession() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(
            listOf(
                ApiResult.Success(routeData, "req_7_start"),
                ApiResult.Success(routeData, "req_7_reroute")
            )
        )
        val location = FakeLocation(fix)
        val haptics = CountingHaptics()
        val nav = navigation(api, location, haptics = haptics)
        nav.start("nearest pharmacy")

        location.fixes.tryEmit(farAway)

        assertEquals(2, api.bodies.size)
        assertEquals(1, haptics.pulses)
        assertTrue(nav.state.value.active)
        assertEquals(0, nav.state.value.stepIndex)
        assertFalse(nav.state.value.offRoute)

        location.fixes.tryEmit(farAway)

        assertEquals(2, api.bodies.size)
        assertEquals(1, haptics.pulses)
    }

    @Test
    fun startingAgainAfterAPreviousTripResetsStateEvenWhenTheNewStartFails() = runBlocking {
        val fix = LocationFix(30.0444, 31.2357, 5f, 1_000L)
        val api = FakeApi(listOf(ApiResult.Success(routeData, "req_8")))
        val location = FakeLocation(fix)
        val nav = navigation(api, location)
        nav.start("nearest pharmacy")
        assertTrue(nav.state.value.active)

        location.fix = null

        val started = nav.start("a different place")

        assertFalse(started)
        assertEquals(NavState(), nav.state.value)
    }
}
