package eg.assistive.core.json

import eg.assistive.core.model.DistanceBand
import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.Side
import java.time.Instant

sealed class EventParse {
    data class Valid(val event: Event) : EventParse()
    data class Invalid(val errors: List<String>) : EventParse()
}

/**
 * Event contract v1 (contracts/event.schema.json) in Kotlin. Both sides are checked against the same
 * fixtures (contracts/fixtures), so they cannot drift apart silently.
 *
 * [strict] = exactly the schema: unknown fields are rejected and `timestamp` is required.
 * Not strict = tolerant reader for runtime and replay: unknown fields ignored, `timestamp` optional,
 * and the proposed `track_id` is read if present.
 */
object EventJson {
    val SOURCES = setOf(
        "object_detection", "vehicle_detection", "ocr", "scene_understanding",
        "currency", "face", "screen", "navigation", "sos", "system",
    )
    val TYPES = setOf(
        "vehicle_approaching", "obstacle_ahead", "person_too_close", "sos", "text_detected",
        "scene_description", "notification", "currency_detected", "face_known", "face_unknown",
    )
    private val FIELDS = setOf("id", "source", "type", "label", "side", "distance_band", "confidence", "risk", "timestamp")
    private val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")

    fun parse(json: Map<String, Any?>, strict: Boolean = true): EventParse {
        val errors = ArrayList<String>()
        if (strict) json.keys.filter { it !in FIELDS }.forEach { errors.add("unknown field: $it") }

        fun str(name: String, required: Boolean, max: Int): String? {
            val v = json[name]
            if (v == null) { if (required) errors.add("missing $name"); return null }
            if (v !is String) { errors.add("$name must be a string"); return null }
            if (required && v.isEmpty()) errors.add("$name must not be empty")
            if (v.length > max) errors.add("$name is longer than $max")
            return v
        }

        fun <T> enumValue(name: String, allowed: Map<String, T>, required: Boolean): T? {
            val v = json[name]
            if (v == null) { if (required) errors.add("missing $name"); return null }
            val found = (v as? String)?.let { allowed[it] }
            if (found == null) errors.add("$name must be one of ${allowed.keys}")
            return found
        }

        val id = str("id", true, 64)
        val source = enumValue("source", SOURCES.associateWith { it }, true)
        val type = enumValue("type", TYPES.associateWith { it }, true)
        val label = str("label", false, 120)
        val side = enumValue("side", Side.values().associateBy { it.name.lowercase() }, false)
        val band = enumValue("distance_band", DistanceBand.values().associateBy { it.name.lowercase() }, false)
        val risk = enumValue("risk", Priority.values().associateBy { it.name.lowercase() }, false)

        var confidence: Double? = null
        json["confidence"]?.let {
            if (it !is Double || it !in 0.0..1.0) errors.add("confidence must be a number between 0 and 1") else confidence = it
        }

        var timestampMs = 0L
        val ts = json["timestamp"]
        if (ts == null) {
            if (strict) errors.add("missing timestamp")
        } else if (ts !is String || !TIMESTAMP.matches(ts)) {
            errors.add("timestamp must be ISO 8601 UTC, like 2026-10-15T12:00:00Z")
        } else {
            timestampMs = try { Instant.parse(ts).toEpochMilli() } catch (e: Exception) { errors.add("timestamp is not a real date"); 0L }
        }

        val trackId = (json["track_id"] as? Double)?.toInt()
        if (errors.isNotEmpty()) return EventParse.Invalid(errors)
        return EventParse.Valid(
            Event(
                id = id!!, source = source!!, type = type!!, label = label ?: "",
                side = side ?: Side.UNKNOWN, distanceBand = band ?: DistanceBand.UNKNOWN,
                confidence = confidence, risk = risk, timestampMs = timestampMs, trackId = trackId,
            ),
        )
    }

    fun parseText(text: String, strict: Boolean = true): EventParse = try {
        parse(Json.parseObject(text), strict)
    } catch (e: JsonException) {
        EventParse.Invalid(listOf("invalid JSON: ${e.message}"))
    }
}
