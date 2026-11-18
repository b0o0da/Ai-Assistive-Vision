package avn.engine.intake

import avn.contracts.Event
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What [EventIntake] decided about one incoming event. */
sealed interface IntakeResult {
    data class Accepted(val event: Event) : IntakeResult

    /** [reason] names the field and the rule. It never contains the event's label or other content. */
    data class Rejected(val reason: String) : IntakeResult
}

/**
 * WS16-02: the engine's front door for [Event]s from the tracker and other producers.
 * Checks every rule in `contracts/schemas/event.schema.json` that Kotlin types alone do not:
 * all nine fields present (side and distance_band may be null but not missing), no extra field,
 * id pattern, label length, confidence 0..1, risk 0..100, timestamp not negative.
 *
 * Never throws: a bad event becomes [IntakeResult.Rejected] so one broken producer cannot crash the engine.
 */
object EventIntake {
    /** Same order and names as `required` in event.schema.json. */
    val REQUIRED_FIELDS =
        listOf("id", "source", "type", "label", "side", "distance_band", "confidence", "risk", "timestamp")

    private val ID_PATTERN = Regex("^evt_[A-Za-z0-9_-]{1,64}$")
    private const val MAX_LABEL = 64
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): IntakeResult {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return IntakeResult.Rejected("not JSON")
        }
        val obj = (element as? JsonObject) ?: return IntakeResult.Rejected("not a JSON object")
        return parse(obj)
    }

    fun parse(obj: JsonObject): IntakeResult {
        val missing = REQUIRED_FIELDS.filter { it !in obj.keys }
        if (missing.isNotEmpty()) return IntakeResult.Rejected("missing field(s): ${missing.joinToString()}")
        val extra = obj.keys - REQUIRED_FIELDS.toSet()
        if (extra.isNotEmpty()) return IntakeResult.Rejected("unknown field(s): ${extra.sorted().joinToString()}")

        val event = try {
            json.decodeFromJsonElement(Event.serializer(), obj.jsonObject)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: wrong type or enum value.
            return IntakeResult.Rejected("field has the wrong type or an unknown enum value")
        }
        return check(event)
    }

    /** Value rules for an already typed [Event] (for producers that build Events in Kotlin). */
    fun check(event: Event): IntakeResult = when {
        !ID_PATTERN.matches(event.id) -> IntakeResult.Rejected("id must match evt_<1-64 of A-Z a-z 0-9 _ ->")
        event.label.length > MAX_LABEL -> IntakeResult.Rejected("label longer than $MAX_LABEL characters")
        event.confidence.isNaN() || event.confidence !in 0.0..1.0 -> IntakeResult.Rejected(
            "confidence must be between 0 and 1"
        )
        event.risk !in 0..100 -> IntakeResult.Rejected("risk must be between 0 and 100")
        event.timestamp < 0 -> IntakeResult.Rejected("timestamp must not be negative")
        else -> IntakeResult.Accepted(event)
    }
}
