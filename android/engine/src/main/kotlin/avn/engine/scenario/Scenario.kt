package avn.engine.scenario

import avn.contracts.Event
import avn.contracts.SpeechCandidate
import avn.engine.Decision
import avn.engine.Reason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A replayable engine test (WS16-01): a timeline of inputs plus the decisions we expect.
 * Format reference: engine/src/test/resources/scenarios/SCHEMA.md.
 */
@Serializable
data class Scenario(
    val name: String,
    val description: String,
    val steps: List<ScenarioStep>,
    val expect: List<ExpectedDecision>
)

/** Exactly one of [event], [candidate] or [context] is set. [atMs] is relative to the scenario start. */
@Serializable
data class ScenarioStep(
    @SerialName("at_ms") val atMs: Long,
    val event: Event? = null,
    val candidate: SpeechCandidate? = null,
    val context: ContextChange? = null
)

/** Mirrors EngineInput.updateContext(...) in architecture §5. Null means "unchanged". */
@Serializable
data class ContextChange(
    val listening: Boolean? = null,
    val speaking: Boolean? = null,
    val moving: Boolean? = null,
    @SerialName("navigation_active") val navigationActive: Boolean? = null,
    val offline: Boolean? = null,
    @SerialName("sos_active") val sosActive: Boolean? = null
) {
    val isEmpty: Boolean
        get() = listOf(listening, speaking, moving, navigationActive, offline, sosActive).all { it == null }
}

/** [eventId] matches an Event.id or a SpeechCandidate.eventId from the steps. */
@Serializable
data class ExpectedDecision(
    @SerialName("event_id") val eventId: String,
    val decision: Decision,
    val reason: Reason? = null
)
