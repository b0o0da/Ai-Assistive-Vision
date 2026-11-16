package avn.engine.scenario

import avn.contracts.Event
import avn.contracts.SpeechCandidate
import avn.engine.Decision
import avn.engine.Reason
import avn.engine.decisionFromWire
import avn.engine.wire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
    @Serializable(with = DecisionWireSerializer::class) val decision: Decision,
    @Serializable(with = ReasonWireSerializer::class) val reason: Reason? = null
)

/** Reads and writes [Decision] by its log name (`spoken`, `queued`, ...). */
object DecisionWireSerializer : KSerializer<Decision> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("avn.engine.Decision", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Decision) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): Decision {
        val value = decoder.decodeString()
        return decisionFromWire(value) ?: throw SerializationException("unknown decision '$value'")
    }
}

/** Reads and writes [Reason] by its log name (`cooldown`, `queue_overflow`, ...). */
object ReasonWireSerializer : KSerializer<Reason> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("avn.engine.Reason", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Reason) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): Reason {
        val value = decoder.decodeString()
        return Reason.fromWire(value) ?: throw SerializationException("unknown reason '$value'")
    }
}
