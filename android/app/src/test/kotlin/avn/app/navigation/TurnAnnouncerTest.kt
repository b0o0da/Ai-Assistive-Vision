package avn.app.navigation

import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.LatLon
import avn.contracts.Maneuver
import avn.contracts.RouteStep
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAnnouncerTest {
    private class FakeEngineInput : EngineInput {
        val submitted = mutableListOf<SpeechCandidate>()
        override fun submit(event: Event) = throw UnsupportedOperationException("unused by TurnAnnouncer")
        override fun submit(candidate: SpeechCandidate) {
            submitted += candidate
        }
    }

    @Test
    fun announceSubmitsTheStepInstructionAsATimeSensitiveNavigationCandidate() {
        val engineInput = FakeEngineInput()
        val announcer = TurnAnnouncer(engineInput)
        val step = RouteStep("Turn right onto Main St", Maneuver.TURN_RIGHT, 80.0, LatLon(30.0500, 31.2450))

        announcer.announce(step)

        assertEquals(1, engineInput.submitted.size)
        val candidate = engineInput.submitted.single()
        assertEquals("Turn right onto Main St", candidate.text)
        assertEquals(Source.NAVIGATION, candidate.source)
        assertEquals(EventType.NAV_INSTRUCTION, candidate.type)
        assertTrue(candidate.timeSensitive)
        assertEquals(null, candidate.eventId)
    }
}
