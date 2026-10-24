package avn.engine

import avn.contracts.Event
import avn.contracts.SpeechCandidate

/** The only way anything reaches the speaker. Producers submit; the engine decides. */
interface EngineInput {
    fun submit(event: Event)

    fun submit(candidate: SpeechCandidate)

    /**
     * Voice and navigation report their state here so neither needs to know the other's rules
     * (architecture.md section 5, "Voice interaction"; see [InterruptRules.updateContext] for
     * what an implementation does with these flags).
     *
     * Defaults to a no-op so existing implementers that don't act on context -- today's
     * [avn.app.core.FakeEngineInput] before AVN-55, and any test double written against the
     * previous two-method shape of this interface -- keep compiling unchanged.
     */
    fun updateContext(
        listening: Boolean = false,
        speaking: Boolean = false,
        moving: Boolean = false,
        navigationActive: Boolean = false,
        offline: Boolean = false,
        sosActive: Boolean = false,
    ) {
    }
}
