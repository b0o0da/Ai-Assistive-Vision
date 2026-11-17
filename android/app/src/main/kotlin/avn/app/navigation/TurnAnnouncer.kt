package avn.app.navigation

import avn.contracts.EventType
import avn.contracts.RouteStep
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import javax.inject.Inject

/** Forwards a route step's instruction to the engine. Submitting is the only side effect; the engine decides if and when it is spoken. */
class TurnAnnouncer @Inject constructor(private val engineInput: EngineInput) {
    fun announce(step: RouteStep) {
        engineInput.submit(
            SpeechCandidate(
                text = step.instruction,
                source = Source.NAVIGATION,
                type = EventType.NAV_INSTRUCTION,
                timeSensitive = true
            )
        )
    }
}
