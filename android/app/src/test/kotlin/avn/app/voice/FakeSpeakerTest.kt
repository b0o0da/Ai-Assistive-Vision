package avn.app.voice

import avn.contracts.Level
import avn.contracts.Source
import avn.contracts.SpeechRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSpeakerTest {
    private var now = 5_000L
    private val logged = mutableListOf<Map<String, Any?>>()
    private val speaker = FakeSpeaker({ _, _, fields -> logged.add(fields) }, { now++ })
    private val holding = FakeSpeaker(silentVoiceLog, { now++ }, autoFinish = false)

    private fun request(id: String, text: String, level: Level = Level.INFO) =
        SpeechRequest(id, text, level, Source.SYSTEM, interruptible = level == Level.INFO)

    private fun failsWith(block: () -> Unit): String {
        try {
            block()
        } catch (e: AssertionError) {
            return e.message.orEmpty()
        }
        throw IllegalStateException("expected an AssertionError")
    }

    @Test
    fun recordsTextPriorityAndTime() {
        speaker.speak(request("a", "Car approaching from the left.", Level.CRITICAL))
        speaker.speak(request("b", "Chair ahead."))
        val records = speaker.requests
        assertEquals(listOf("Car approaching from the left.", "Chair ahead."), speaker.spokenTexts)
        assertEquals(listOf(Level.CRITICAL, Level.INFO), records.map { it.request.level })
        assertTrue(records[0].atMs < records[1].atMs)
    }

    @Test
    fun keepsEveryRequestWhileTheDebugScreenShowsTheLastTwenty() {
        repeat(25) { speaker.speak(request("u$it", "text $it")) }
        assertEquals(25, speaker.requests.size)
        assertEquals(20, speaker.spoken.value.size)
        assertEquals("text 24", speaker.spoken.value.last().text)
        assertEquals("text 5", speaker.spoken.value.first().text)
    }

    @Test
    fun speechEndsAtOnceByDefault() {
        speaker.speak(request("a", "Hello."))
        assertFalse(speaker.isSpeaking)
        assertNull(speaker.currentUtteranceId)
    }

    @Test
    fun aRequestKeepsSpeakingUntilItIsFinished() {
        holding.speak(request("a", "Hello."))
        assertTrue(holding.isSpeaking)
        assertEquals("a", holding.currentUtteranceId)
        holding.finishCurrent()
        assertFalse(holding.isSpeaking)
    }

    @Test
    fun aCriticalPreemptsAnInfoRequest() {
        holding.speak(request("info", "Long description."))
        holding.speak(request("crit", "Stop.", Level.CRITICAL))
        holding.assertInterrupted("info")
        assertEquals(InterruptReason.PREEMPTED, holding.interruptions.single().reason)
        assertEquals("crit", holding.currentUtteranceId)
        holding.assertNoOverlap()
    }

    @Test
    fun aCriticalDoesNotInterruptAnotherCritical() {
        holding.speak(request("c1", "First.", Level.CRITICAL))
        holding.speak(request("c2", "Second.", Level.CRITICAL))
        assertTrue(holding.interruptions.isEmpty())
        assertEquals(1, holding.overlaps)
    }

    @Test
    fun stopByIdInterruptsOnlyTheCurrentRequest() {
        holding.speak(request("a", "Hello."))
        holding.stop("other")
        assertTrue(holding.isSpeaking)
        assertTrue(holding.interruptions.isEmpty())
        holding.stop("a")
        assertFalse(holding.isSpeaking)
        holding.assertInterrupted("a")
        assertEquals(listOf("other", "a"), holding.stops.map { it.utteranceId })
    }

    @Test
    fun stopWithoutAnIdIsAFlush() {
        holding.speak(request("a", "Hello."))
        holding.stop()
        assertFalse(holding.isSpeaking)
        holding.assertFlushed()
        assertEquals(InterruptReason.STOPPED, holding.interruptions.single().reason)
    }

    @Test
    fun speakingOverANonCriticalRequestIsCountedAsAnOverlap() {
        holding.speak(request("a", "One."))
        holding.speak(request("b", "Two."))
        assertEquals(1, holding.overlaps)
        assertTrue(failsWith { holding.assertNoOverlap() }.contains("overlapped"))
    }

    @Test
    fun assertSpokePassesAndExplainsAFailure() {
        speaker.speak(request("a", "Door ahead.", Level.WARNING))
        speaker.assertSpoke("Door ahead.")
        speaker.assertSpoke("Door ahead.", Level.WARNING)
        assertTrue(failsWith { speaker.assertSpoke("Door ahead.", Level.CRITICAL) }.contains("CRITICAL"))
        assertTrue(failsWith { speaker.assertSpoke("Nothing like this") }.contains("Door ahead."))
        speaker.assertSpokeContaining("door")
        assertTrue(failsWith { speaker.assertSpokeContaining("window") }.contains("window"))
    }

    @Test
    fun assertSpokeInOrderAllowsOtherRequestsBetween() {
        listOf("one", "noise", "two", "three").forEachIndexed { i, text -> speaker.speak(request("u$i", text)) }
        speaker.assertSpokeInOrder("one", "two", "three")
        assertTrue(failsWith { speaker.assertSpokeInOrder("two", "one") }.contains("in order"))
    }

    @Test
    fun assertNothingSpoken() {
        speaker.assertNothingSpoken()
        speaker.speak(request("a", "Hello."))
        assertTrue(failsWith { speaker.assertNothingSpoken() }.contains("Hello."))
    }

    @Test
    fun assertNotInterruptedAndFlushedFailWhenFalse() {
        holding.speak(request("a", "Hello."))
        holding.assertNotInterrupted("a")
        assertTrue(failsWith { holding.assertInterrupted("a") }.contains("\"a\""))
        assertTrue(failsWith { holding.assertFlushed() }.contains("stop(null)"))
    }

    @Test
    fun resetForgetsEverything() {
        holding.speak(request("a", "Hello."))
        holding.stop()
        holding.reset()
        assertTrue(holding.requests.isEmpty())
        assertTrue(holding.stops.isEmpty())
        assertTrue(holding.interruptions.isEmpty())
        assertTrue(holding.spoken.value.isEmpty())
        assertFalse(holding.isSpeaking)
    }

    @Test
    fun theLogNeverContainsTheSpokenText() {
        speaker.speak(request("a", "Maria is at the door."))
        speaker.stop("a")
        assertTrue(logged.isNotEmpty())
        assertTrue(logged.none { fields -> fields.values.any { it.toString().contains("Maria") } })
        assertEquals(21, logged.first()["chars"])
    }
}
