package avn.engine

import avn.contracts.Event
import avn.contracts.SpeechCandidate

/** The only way anything reaches the speaker. Producers submit; the engine decides. */
interface EngineInput {
    fun submit(event: Event)

    fun submit(candidate: SpeechCandidate)
}
