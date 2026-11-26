package avn.app.vision

import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.contracts.OcrResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// WS07-01: OCR interface and fake. The real ML Kit engine (WS07-03) implements the same interface.

/** Box in pixels of the analysed frame, same convention as [Detection]. */
data class TextBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    init {
        require(w >= 0 && h >= 0) { "box width and height must not be negative" }
    }
}

/** One recognised line or block of text. */
data class OcrBlock(val text: String, val box: TextBox, val confidence: Double) {
    init {
        require(text.isNotBlank()) { "OCR block text must not be blank" }
        require(confidence in 0.0..1.0) { "OCR confidence must be between 0 and 1" }
    }
}

/** Why a reading has no text (WS07-03). Each one maps to a short sentence the engine can speak. */
enum class NoTextReason(val spoken: String) {
    EMPTY_FRAME("I don't have a picture to read."),
    BLURRY("The picture is too blurry to read. Hold the phone steady and try again."),
    NO_TEXT("I can't see any text."),
    ENGINE_ERROR("I can't read text right now.")
}

/**
 * Everything one OCR pass found. No blocks means no text was found, which is a normal result;
 * [noText] then says why, when the engine knows.
 */
data class OcrReading(
    val blocks: List<OcrBlock>,
    val language: String,
    val source: String,
    val noText: NoTextReason? = null
) {
    /** Blocks top to bottom, one per line. */
    val text: String get() = blocks.joinToString("\n") { it.text }

    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Collapses to the shared contract type for consumers that only need the text. */
    fun toResult(): OcrResult = OcrResult(
        text = text,
        confidence = if (blocks.isEmpty()) 0.0 else blocks.map { it.confidence }.average(),
        language = language,
        source = source
    )

    companion object {
        fun empty(source: String, language: String = "en") = OcrReading(emptyList(), language, source)
    }
}

interface OcrEngine {
    suspend fun read(frame: Frame): OcrReading
}

/**
 * Replays queued readings in order, then returns an empty reading.
 * Load readings with [enqueue] or from fixture JSON with [OcrFixtures.parse].
 */
@Singleton
class FakeOcrEngine @Inject constructor(private val log: JsonLinesLogger) : OcrEngine {
    private val pending = ArrayDeque<OcrReading>()

    @Synchronized
    fun enqueue(reading: OcrReading) {
        pending.addLast(reading)
    }

    override suspend fun read(frame: Frame): OcrReading {
        val next = synchronized(this) { pending.removeFirstOrNull() }
        // Only counts are logged; OCR text is content and never written to the log.
        log.log("ocr", "fake_read", fields = mapOf("blocks" to (next?.blocks?.size ?: 0)))
        return next ?: OcrReading.empty(SOURCE)
    }

    companion object {
        const val SOURCE = "fake"
    }
}

/** Reads OCR fixture files (one JSON file per case in tests/fixtures/ocr/). Format: see tests/fixtures/ocr/README.md. */
object OcrFixtures {
    fun parse(json: String): OcrReading {
        val root = Json.parseToJsonElement(json).jsonObject
        val blocks = root.getValue("blocks").jsonArray.map { element ->
            val block = element.jsonObject
            val box = block.getValue("box").jsonObject
            OcrBlock(
                text = block.getValue("text").jsonPrimitive.content,
                box = TextBox(
                    x = box.getValue("x").jsonPrimitive.float,
                    y = box.getValue("y").jsonPrimitive.float,
                    w = box.getValue("w").jsonPrimitive.float,
                    h = box.getValue("h").jsonPrimitive.float
                ),
                confidence = block.getValue("confidence").jsonPrimitive.double
            )
        }
        return OcrReading(
            blocks = blocks,
            language = root["language"]?.jsonPrimitive?.content ?: "en",
            source = root["source"]?.jsonPrimitive?.content ?: "fixture"
        )
    }
}
