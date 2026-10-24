package avn.app.vision

import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.Navigation
import avn.contracts.Event
import avn.contracts.NavState
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
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

private class FakeNavigation(
    private val startResult: Boolean = true,
    private val startFailure: Exception? = null
) : Navigation {
    override val state: StateFlow<NavState> = MutableStateFlow(NavState())
    val startedWith = mutableListOf<String>()

    override suspend fun start(destination: String): Boolean {
        startedWith.add(destination)
        startFailure?.let { throw it }
        return startResult
    }

    override fun cancel() {}
}

/**
 * Covers [OcrDestinationResolver]'s glue around [OcrDestinationConfirmState] -- propose/confirm/
 * dismiss speaking through [EngineInput] and [Navigation.start], with fakes instead of ML Kit/
 * RealNavigation, same split as [avn.app.sos.RealSosTest] does for [avn.app.sos.RealSos]. [confirm]
 * launches its [Navigation.start] call on the resolver's own background coroutine, so the
 * confirm/dismiss-only tests poll [OcrDestinationResolver.pendingCandidate] rather than controlling
 * a virtual clock -- there's no real delay involved, just a dispatcher hop, so the poll resolves
 * almost immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrDestinationResolverTest {
    private val log = JsonLinesLogger(RuntimeEnvironment.getApplication())

    private fun awaitNoPendingCandidate(resolver: OcrDestinationResolver, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (resolver.pendingCandidate != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertNull("confirm()'s async navigation.start never completed", resolver.pendingCandidate)
    }

    @Test
    fun implausibleTextIsIgnored() {
        val engineInput = RecordingEngineInput()
        val resolver = OcrDestinationResolver(FakeNavigation(), engineInput, log)

        resolver.onTextDetected("1")

        assertNull(resolver.pendingCandidate)
        assertTrue(engineInput.speeches.isEmpty())
    }

    @Test
    fun plausibleTextProposesAndSpeaksAConfirmationPrompt() {
        val engineInput = RecordingEngineInput()
        val resolver = OcrDestinationResolver(FakeNavigation(), engineInput, log)

        resolver.onTextDetected("Pharmacy", nowSeconds = 0.0)

        assertEquals("Pharmacy", resolver.pendingCandidate)
        assertEquals(1, engineInput.speeches.size)
        assertTrue(engineInput.speeches[0].text.contains("Pharmacy"))
    }

    @Test
    fun secondCandidateWhilePendingIsIgnored() {
        val engineInput = RecordingEngineInput()
        val resolver = OcrDestinationResolver(FakeNavigation(), engineInput, log)

        resolver.onTextDetected("Pharmacy", nowSeconds = 0.0)
        resolver.onTextDetected("Gym", nowSeconds = 0.1)

        assertEquals("Pharmacy", resolver.pendingCandidate)
        assertEquals(1, engineInput.speeches.size)
    }

    @Test
    fun confirmStartsNavigationAndClearsPendingOnSuccess() {
        val engineInput = RecordingEngineInput()
        val navigation = FakeNavigation(startResult = true)
        val resolver = OcrDestinationResolver(navigation, engineInput, log)

        resolver.onTextDetected("Pharmacy", nowSeconds = 0.0)
        resolver.confirm(nowSeconds = 1.0)
        awaitNoPendingCandidate(resolver)

        assertEquals(listOf("Pharmacy"), navigation.startedWith)
        // RealNavigation itself speaks the first instruction on success; this resolver stays quiet.
        assertEquals(1, engineInput.speeches.size)
    }

    @Test
    fun confirmSpeaksFailureWhenNavigationCannotFindARoute() {
        val engineInput = RecordingEngineInput()
        val navigation = FakeNavigation(startResult = false)
        val resolver = OcrDestinationResolver(navigation, engineInput, log)

        resolver.onTextDetected("Nowhere", nowSeconds = 0.0)
        resolver.confirm(nowSeconds = 1.0)
        awaitNoPendingCandidate(resolver)

        assertEquals(2, engineInput.speeches.size)
        assertTrue(engineInput.speeches.last().text.contains("couldn't find a route"))
    }

    @Test
    fun confirmSpeaksFailureWhenNavigationThrows() {
        val engineInput = RecordingEngineInput()
        val navigation = FakeNavigation(startFailure = RuntimeException("boom"))
        val resolver = OcrDestinationResolver(navigation, engineInput, log)

        resolver.onTextDetected("Nowhere", nowSeconds = 0.0)
        resolver.confirm(nowSeconds = 1.0)
        awaitNoPendingCandidate(resolver)

        assertTrue(engineInput.speeches.last().text.contains("couldn't find a route"))
    }

    @Test
    fun confirmWithNothingPendingIsANoOp() {
        val engineInput = RecordingEngineInput()
        val navigation = FakeNavigation()
        val resolver = OcrDestinationResolver(navigation, engineInput, log)

        resolver.confirm(nowSeconds = 0.0)

        assertTrue(navigation.startedWith.isEmpty())
        assertTrue(engineInput.speeches.isEmpty())
    }

    @Test
    fun dismissSpeaksAndClearsPendingWithoutCallingNavigation() {
        val engineInput = RecordingEngineInput()
        val navigation = FakeNavigation()
        val resolver = OcrDestinationResolver(navigation, engineInput, log)

        resolver.onTextDetected("Pharmacy", nowSeconds = 0.0)
        resolver.dismiss(nowSeconds = 1.0)

        assertNull(resolver.pendingCandidate)
        assertTrue(navigation.startedWith.isEmpty())
        assertEquals(2, engineInput.speeches.size)
        assertTrue(engineInput.speeches.last().text.contains("not navigating"))
    }
}
