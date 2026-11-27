package avn.app.vision

import avn.contracts.EventType
import avn.contracts.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS07-05 (AVN-132): what the user hears after "read this". */
class OcrSpeechSummaryTest {
    private val config = OcrSpeechConfig(maxChars = 120, uncertainBelow = 0.7, spellDigitRunsFrom = 5)
    private val summary = OcrSpeechSummary(config)

    private fun reading(vararg lines: Pair<String, Double>) = OcrReading(
        blocks = lines.mapIndexed { i, (text, conf) -> OcrBlock(text, TextBox(0f, i * 40f, 200f, 30f), conf) },
        language = "en",
        source = "ml_kit"
    )

    @Test
    fun signLinesBecomeSentencesInReadingOrder() {
        val spoken = summary.spokenText(reading("PHARMACY" to 0.95, "Open until 10 PM" to 0.9))
        assertEquals("PHARMACY. Open until 10 PM.", spoken)
    }

    @Test
    fun test_summary_length_cap() {
        val long = (1..30).joinToString(" ") { "Take one tablet with water." }
        val spoken = summary.spokenText(reading(long to 0.95))

        assertTrue("was ${spoken.length}: $spoken", spoken.length <= config.maxChars)
        assertTrue(spoken.endsWith(OcrSpeechSummary.CONTINUES))
        assertTrue("cut at a sentence end: $spoken", spoken.startsWith("Take one tablet with water. Take"))
        assertFalse(spoken.contains("water Take"))
    }

    @Test
    fun capFallsBackToAWordBoundaryWhenThereIsNoSentenceEnd() {
        val words = (1..60).joinToString(" ") { "word$it" }.replace(Regex("\\d"), "")
        val spoken = summary.spokenText(reading(words to 0.95))

        assertTrue(spoken.length <= config.maxChars)
        assertTrue(spoken, spoken.endsWith("word. ${OcrSpeechSummary.CONTINUES}"))
    }

    @Test
    fun test_summary_low_confidence() {
        val spoken = summary.spokenText(reading("EXIT" to 0.55, "Gate 3" to 0.6))
        assertEquals("I may be wrong. It looks like it says: EXIT. Gate 3.", spoken)
    }

    @Test
    fun confidentTextHasNoUncertaintyPrefix() {
        val spoken = summary.spokenText(reading("EXIT" to 0.9))
        assertFalse(spoken.startsWith("I may be wrong"))
    }

    @Test
    fun numbersAreReadClearly() {
        val spoken = summary.spokenText(
            reading(
                "Paracetamol 500mg" to 0.95,
                "Call 01001234567" to 0.95,
                "Price 10,000 EGP on the 1st floor" to 0.95,
                "Year 2026, dose 2.5ml" to 0.95
            )
        )
        assertEquals(
            "Paracetamol 500 mg. Call 0 1 0 0 1 2 3 4 5 6 7. Price 10,000 EGP on the 1st floor. " +
                "Year 2026, dose 2.5 ml.",
            spoken
        )
    }

    @Test
    fun noTextSpeaksTheReason() {
        assertEquals("I can't see any text.", summary.spokenText(OcrReading.empty("ml_kit")))
        val blurry = OcrReading(emptyList(), "en", "ml_kit", noText = NoTextReason.BLURRY)
        assertEquals(NoTextReason.BLURRY.spoken, summary.spokenText(blurry))
    }

    @Test
    fun candidateIsAUserRequestedCameraTextResult() {
        val candidate = summary.candidate(reading("STOP" to 0.9), eventId = "ocr_1")

        assertEquals("STOP.", candidate.text)
        assertEquals(Source.CAMERA, candidate.source)
        assertEquals(EventType.TEXT_RESULT, candidate.type)
        assertTrue(candidate.userRequested)
        assertFalse(candidate.timeSensitive)
        assertEquals("ocr_1", candidate.eventId)
    }

    @Test
    fun shippedConfigLoadsAndMatchesDefault() {
        assertEquals(OcrSpeechConfig.DEFAULT, OcrSpeechConfig.load())
    }
}
