package avn.app.vision

import avn.contracts.DistanceBand
import avn.contracts.Side
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One frame of detector output at [timestampMs]. */
data class DetectionFrame(val timestampMs: Long, val detections: List<Detection>)

/** The correct band and side for one object of a sequence's last frame. */
data class ExpectedObject(val label: String, val band: DistanceBand, val side: Side)

/**
 * WS05-03: a detection stream from `tests/fixtures/detections/` (format:
 * `detection_sequence.schema.json`), replayed by [FakeDetector.load].
 */
data class DetectionSequence(
    val name: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val expected: List<ExpectedObject>,
    val frames: List<DetectionFrame>
)

/**
 * Parses detection sequence JSON by hand through JsonElement, like the other fixture loaders in
 * `:app` (no serialization plugin here). Throws on a broken file.
 */
object DetectionSequences {
    fun parse(json: String): DetectionSequence {
        val root = Json.parseToJsonElement(json).jsonObject
        val frames = root.getValue("frames").jsonArray.map { element ->
            val frame = element.jsonObject
            val timestampMs = frame.getValue("timestampMs").jsonPrimitive.long
            DetectionFrame(
                timestampMs = timestampMs,
                detections = frame.getValue("detections").jsonArray.map { d ->
                    val box = d.jsonObject
                    Detection(
                        label = box.getValue("label").jsonPrimitive.content,
                        confidence = box.getValue("confidence").jsonPrimitive.float,
                        x = box.getValue("x").jsonPrimitive.float,
                        y = box.getValue("y").jsonPrimitive.float,
                        w = box.getValue("w").jsonPrimitive.float,
                        h = box.getValue("h").jsonPrimitive.float,
                        timestampMs = timestampMs
                    )
                }
            )
        }
        val expected = root.getValue("expected").jsonArray.map { e ->
            val obj = e.jsonObject
            ExpectedObject(
                label = obj.getValue("label").jsonPrimitive.content,
                band = DistanceBand.valueOf(obj.getValue("band").jsonPrimitive.content.uppercase()),
                side = Side.valueOf(obj.getValue("side").jsonPrimitive.content.uppercase())
            )
        }
        return DetectionSequence(
            name = root.getValue("name").jsonPrimitive.content,
            frameWidth = root.getValue("frameWidth").jsonPrimitive.int,
            frameHeight = root.getValue("frameHeight").jsonPrimitive.int,
            expected = expected,
            frames = frames
        )
    }
}
