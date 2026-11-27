package avn.app.vision

import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// WS07-05 (AVN-132): turn one OCR reading into one short, speakable Info candidate for the engine.

/** Numbers for [OcrSpeechSummary], from `config/ocr_speech.json` (PROPOSED). */
data class OcrSpeechConfig(
    /** Longest spoken text, in characters, before the "text continues" note. */
    val maxChars: Int,
    /** Mean line confidence below this is spoken with "I may be wrong". */
    val uncertainBelow: Double,
    /** Digit runs at least this long (phone numbers, codes) are spoken digit by digit. */
    val spellDigitRunsFrom: Int
) {
    init {
        require(maxChars >= MIN_CHARS) { "maxChars must be at least $MIN_CHARS" }
        require(uncertainBelow in 0.0..1.0) { "uncertainBelow must be between 0 and 1" }
        require(spellDigitRunsFrom >= 2) { "spellDigitRunsFrom must be at least 2" }
    }

    companion object {
        const val MIN_CHARS = 40
        val DEFAULT = OcrSpeechConfig(maxChars = 300, uncertainBelow = 0.7, spellDigitRunsFrom = 5)

        fun load(resourcePath: String = "/config/ocr_speech.json"): OcrSpeechConfig {
            val text = OcrSpeechConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching { parse(text) }.getOrDefault(DEFAULT)
        }

        fun parse(json: String): OcrSpeechConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            return OcrSpeechConfig(
                maxChars = root.getValue("maxChars").jsonPrimitive.int,
                uncertainBelow = root.getValue("uncertainBelow").jsonPrimitive.double,
                spellDigitRunsFrom = root.getValue("spellDigitRunsFrom").jsonPrimitive.int
            )
        }
    }
}

/**
 * Builds what the user hears after asking to read text (architecture.md WS-07: "chunk text for
 * TTS", then a `SpeechRequest` at Info level, user-requested). Pure: no I/O, no logging, so the
 * recognised text never leaves memory here.
 *
 * - No text: the reading's [NoTextReason] sentence ("I can't see any text." by default).
 * - Lines are joined into sentences; a line that does not end in punctuation gets a full stop
 *   so TTS pauses between sign lines instead of running them together.
 * - Numbers: a number glued to a unit is split ("500mg" -> "500 mg"); a digit run of at least
 *   [OcrSpeechConfig.spellDigitRunsFrom] digits is spaced out so TTS reads it digit by digit
 *   (phone numbers, codes). Ordinals ("1st", "22nd") and grouped numbers ("10,000") are kept.
 * - Length: capped at [OcrSpeechConfig.maxChars] at a sentence or word boundary, then
 *   "The text continues." so the user knows there is more.
 * - Uncertainty: mean confidence below [OcrSpeechConfig.uncertainBelow] prefixes
 *   "I may be wrong. It looks like it says:".
 */
class OcrSpeechSummary(private val config: OcrSpeechConfig = OcrSpeechConfig.load()) {
    /** The engine candidate: camera source, `text_result`, user-requested (so Info, never above). */
    fun candidate(reading: OcrReading, eventId: String? = null): SpeechCandidate = SpeechCandidate(
        text = spokenText(reading),
        source = Source.CAMERA,
        type = EventType.TEXT_RESULT,
        userRequested = true,
        eventId = eventId
    )

    fun spokenText(reading: OcrReading): String {
        if (reading.isEmpty) return (reading.noText ?: NoTextReason.NO_TEXT).spoken
        val joined = reading.blocks
            .map { it.text.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { line -> if (line.last() in SENTENCE_END) line else "$line." }
        val spoken = cap(readNumbersClearly(joined))
        val confidence = reading.blocks.map { it.confidence }.average()
        return if (confidence < config.uncertainBelow) "$UNCERTAIN_PREFIX $spoken" else spoken
    }

    internal fun readNumbersClearly(text: String): String {
        val unitsSplit = NUMBER_THEN_LETTERS.replace(text) { m ->
            val (digits, letters) = m.destructured
            if (letters.lowercase() in ORDINALS) m.value else "$digits $letters"
        }
        val runPattern = Regex("(?<![\\d.,])\\d{${config.spellDigitRunsFrom},}(?![\\d.,]\\d)")
        return runPattern.replace(unitsSplit) { m -> m.value.toList().joinToString(" ") }
    }

    private fun cap(text: String): String {
        if (text.length <= config.maxChars) return text
        val room = config.maxChars - CONTINUES.length - 1
        val head = text.take(room)
        val sentenceEnd = head.indexOfLast { it in SENTENCE_END }
        val cut = when {
            sentenceEnd >= room / 2 -> head.substring(0, sentenceEnd + 1)
            head.lastIndexOf(' ') > 0 -> head.substring(0, head.lastIndexOf(' ')).trimEnd(',', ';', ':') + "."
            else -> "$head."
        }
        return "$cut $CONTINUES"
    }

    companion object {
        const val UNCERTAIN_PREFIX = "I may be wrong. It looks like it says:"
        const val CONTINUES = "The text continues."
        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_END = setOf('.', '!', '?', ':', ';')
        private val NUMBER_THEN_LETTERS = Regex("\\b(\\d+)([A-Za-z]+)\\b")
        private val ORDINALS = setOf("st", "nd", "rd", "th")
    }
}
