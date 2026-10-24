package avn.app.navigation

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class RecordingEngineInput : EngineInput {
    val events = mutableListOf<Event>()
    val speeches = mutableListOf<SpeechCandidate>()

    override fun submit(event: Event) {
        events.add(event)
    }

    override fun submit(candidate: SpeechCandidate) {
        speeches.add(candidate)
    }
}

private class FixedLocationSource(private val fix: LocationFix?) : LocationSource {
    override fun locations(): Flow<LocationFix> = emptyFlow()

    override suspend fun lastKnown(): LocationFix? = fix
}

private class FakeRouteClient(
    private val result: RouteResult? = null,
    private val failure: Exception? = null
) : RouteClient {
    var lastOrigin: LocationFix? = null
    var lastDestinationQuery: String? = null

    override suspend fun getRoute(origin: LocationFix, destinationQuery: String): RouteResult {
        lastOrigin = origin
        lastDestinationQuery = destinationQuery
        failure?.let { throw it }
        return result ?: error("FakeRouteClient configured with neither result nor failure")
    }
}

/**
 * Covers [RealNavigation]'s glue around [RouteClient]/[LocationSource] -- origin resolution,
 * [NavState] updates and the first-step [Event] submission -- with fakes instead of OkHttp/Fused
 * Location, same split as [avn.app.sos.RealSosTest] does for [avn.app.sos.RealSos]. Turn-by-turn
 * re-narration as the walk progresses is out of scope here (see the class doc on [RealNavigation]
 * itself): this only checks the one instruction it submits up front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealNavigationTest {
    private val log = JsonLinesLogger(RuntimeEnvironment.getApplication())
    private val origin = LocationFix(30.0, 31.0, 5f, 0L)
    private val route = RouteResult(
        steps = listOf(
            RouteStep(instruction = "Head north", maneuver = "start", distanceMeters = 120.0),
            RouteStep(instruction = "Arrive", maneuver = "arrive", distanceMeters = 0.0)
        ),
        distanceMeters = 120.0,
        durationSeconds = 90.0,
        destinationName = "Pharmacy"
    )

    @Test
    fun startWithNoKnownLocationFailsWithoutCallingTheRouteClient() = runBlocking {
        val routeClient = FakeRouteClient(result = route)
        val nav = RealNavigation(routeClient, FixedLocationSource(null), RecordingEngineInput(), log)

        assertFalse(nav.start("Pharmacy"))
        assertNull(routeClient.lastDestinationQuery)
        assertFalse(nav.state.value.active)
    }

    @Test
    fun startResolvesRouteAndSubmitsFirstStepAsEvent() = runBlocking {
        val routeClient = FakeRouteClient(result = route)
        val engineInput = RecordingEngineInput()
        val nav = RealNavigation(routeClient, FixedLocationSource(origin), engineInput, log)

        assertTrue(nav.start("Pharmacy"))

        assertEquals(origin, routeClient.lastOrigin)
        assertEquals("Pharmacy", routeClient.lastDestinationQuery)
        assertTrue(nav.state.value.active)
        assertEquals(0, nav.state.value.stepIndex)
        assertFalse(nav.state.value.offRoute)
        assertEquals(route, nav.lastRoute)

        assertEquals(1, engineInput.events.size)
        assertEquals("Head north", engineInput.events[0].label)
        assertEquals(EventType.NAV_INSTRUCTION, engineInput.events[0].type)
    }

    @Test
    fun startFailsSoftWhenTheRouteClientThrows() = runBlocking {
        val routeClient = FakeRouteClient(failure = RouteRequestException("backend rejected"))
        val engineInput = RecordingEngineInput()
        val nav = RealNavigation(routeClient, FixedLocationSource(origin), engineInput, log)

        assertFalse(nav.start("Nowhere"))
        assertFalse(nav.state.value.active)
        assertTrue(engineInput.events.isEmpty())
        assertNull(nav.lastRoute)
    }

    @Test
    fun cancelClearsStateAndLastRoute() = runBlocking {
        val nav = RealNavigation(FakeRouteClient(result = route), FixedLocationSource(origin), RecordingEngineInput(), log)
        nav.start("Pharmacy")

        nav.cancel()

        assertFalse(nav.state.value.active)
        assertNull(nav.lastRoute)
    }
}
