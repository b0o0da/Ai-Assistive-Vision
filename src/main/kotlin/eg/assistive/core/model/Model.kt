package eg.assistive.core.model

/** Risk level of an event AND level of a spoken message. Order matters: INFO < WARNING < CRITICAL. */
enum class Priority { INFO, WARNING, CRITICAL }

enum class Side { LEFT, CENTER, RIGHT, UNKNOWN }

/** Relative distance only, never meters. [rank] 0 is the closest. */
enum class DistanceBand(val rank: Int) { NEAR(0), MEDIUM(1), FAR(2), UNKNOWN(3) }

/**
 * Event from a pipeline. Contract v1 fields (docs/event-schema.md):
 * id, source, type, label, side, distance_band, confidence, risk, timestamp.
 * [risk] is the producer's own hint (critical / warning / info), optional: the engine computes its own score.
 * [trackId] is NOT in contract v1 (proposed for v1.1); it only improves cooldown when present.
 */
data class Event(
    val id: String,
    val source: String,
    val type: String,
    val label: String = "",
    val side: Side = Side.UNKNOWN,
    val distanceBand: DistanceBand = DistanceBand.UNKNOWN,
    val confidence: Double? = null,
    val risk: Priority? = null,
    val timestampMs: Long = 0L,
    val trackId: Int? = null,
)

/** Something a pipeline wants spoken (OCR text, navigation step, color ...). Goes into EngineInput.submit(). */
data class SpeechCandidate(
    val id: String,
    val source: String,
    val text: String,
    val level: Priority = Priority.INFO,
    /** The user explicitly asked for it ("read this"). Bypasses cooldown and the idle gate. */
    val userRequested: Boolean = false,
    /** Same key = same thing for cooldown. Defaults to source + text. */
    val dedupeKey: String? = null,
    /** Text comes from the user's surroundings (OCR, screen): it is never written to the log. */
    val sensitive: Boolean = false,
    /** How long it may wait in the queue. null = engine default for the level. */
    val ttlMs: Long? = null,
    /** Can a critical alert cut it off? null = yes unless the level is CRITICAL. */
    val interruptible: Boolean? = null,
    /** The event this speech explains, if any. */
    val eventId: String? = null,
    val timestampMs: Long = 0L,
)

/**
 * The one typed request that reaches SpeechOutput.speak(). Contract fields: text, level, source,
 * interruptible, event_id. [utteranceId] is added by the engine so utterance callbacks can be matched.
 */
data class SpeechRequest(
    val utteranceId: String,
    val text: String,
    val level: Priority,
    val source: String,
    val interruptible: Boolean,
    val eventId: String? = null,
)

/**
 * One camera frame / image. Pixels are ARGB ints, row by row. Android code converts a Bitmap into this.
 * [tag] optionally names the frame (for example a fixture id) so test doubles can recognise it.
 */
class Frame(val width: Int, val height: Int, val pixels: IntArray, val tag: String? = null) {
    init {
        require(width > 0 && height > 0) { "empty frame" }
        require(pixels.size == width * height) { "pixels size must be width*height" }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
}
