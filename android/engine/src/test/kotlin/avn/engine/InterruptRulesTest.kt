package avn.engine

import avn.contracts.Level
import avn.contracts.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the "Speech queue and interruptions" table (docs/architecture.md section 5) as far as
 * it can be covered without the STT/wake-word/navigation modules it ultimately integrates with
 * -- see the scope notes on [InterruptRules] for exactly what is deferred and why.
 */
class InterruptRulesTest {
    private lateinit var speaker: FakeSpeaker
    private lateinit var clock: FakeClock
    private lateinit var rules: InterruptRules

    @Before
    fun setUp() {
        speaker = FakeSpeaker()
        clock = FakeClock()
        rules = InterruptRules(speaker, clock, idleDebounceMillis = 1_000)
    }

    private fun request(
        id: String,
        level: Level,
        text: String = id,
        source: Source = Source.CAMERA,
        interruptible: Boolean = true
    ) = avn.contracts.SpeechRequest(
        utteranceId = id,
        text = text,
        level = level,
        source = source,
        interruptible = interruptible
    )

    private fun goIdleAndStationary() {
        rules.updateContext(listening = false, moving = false, navigationActive = false)
        clock.advanceBy(1_000)
        rules.updateContext(listening = false, moving = false, navigationActive = false)
    }

    // ---- Done when: Critical interrupts current speech ----

    @Test
    fun criticalFlushesWhateverIsCurrentlySpeaking() {
        goIdleAndStationary()
        val info = SpeechItem(request("info-1", Level.INFO))
        assertEquals(Decision.SPOKEN, rules.submit(info))
        assertEquals("info-1", rules.speakingNow?.utteranceId)

        val critical = SpeechItem(request("crit-1", Level.CRITICAL))
        assertEquals(Decision.SPOKEN, rules.submit(critical))

        assertEquals("crit-1", rules.speakingNow?.utteranceId)
        assertEquals(listOf("info-1"), speaker.stopped)
        assertEquals(listOf("info-1", "crit-1"), speaker.spoken.map { it.utteranceId })
    }

    @Test
    fun interruptedInfoIsDroppedNotRequeued() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("info-1", Level.INFO)))
        rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))

        assertTrue("interrupted Info must not come back", rules.queuedRequests.isEmpty())
        assertTrue(rules.heldRequests.isEmpty())
    }

    // ---- Done when: Warning queues for next gap ----

    @Test
    fun warningWaitsForTheGapInsteadOfInterrupting() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("info-1", Level.INFO)))
        assertEquals("info-1", rules.speakingNow?.utteranceId)

        val decision = rules.submit(SpeechItem(request("warn-1", Level.WARNING)))

        assertEquals(Decision.QUEUED, decision)
        assertEquals("info-1", rules.speakingNow?.utteranceId) // not interrupted
        assertEquals(listOf("warn-1"), rules.queuedRequests.map { it.utteranceId })

        rules.notifyFinished("info-1")

        assertEquals("warn-1", rules.speakingNow?.utteranceId)
    }

    // ---- Done when: Info spoken only when idle and stationary, or when asked ----

    @Test
    fun infoIsHeldWhileMovingAndSpokenOnceStationaryLongEnough() {
        rules.updateContext(moving = true)
        val decision = rules.submit(SpeechItem(request("info-1", Level.INFO)))

        assertEquals(Decision.HELD, decision)
        assertNull(rules.speakingNow)
        assertEquals(listOf("info-1"), rules.heldRequests.map { it.utteranceId })

        rules.updateContext(moving = false) // stops moving, but debounce hasn't elapsed yet
        assertNull(rules.speakingNow)

        clock.advanceBy(1_000)
        rules.updateContext(moving = false) // re-poked after the debounce window

        assertEquals("info-1", rules.speakingNow?.utteranceId)
    }

    @Test
    fun userRequestedInfoBypassesTheIdleCheck() {
        rules.updateContext(moving = true) // definitely not idle/stationary
        val decision = rules.submit(SpeechItem(request("ask-1", Level.INFO), userRequested = true))

        assertEquals(Decision.SPOKEN, decision)
        assertEquals("ask-1", rules.speakingNow?.utteranceId)
    }

    @Test
    fun userRequestedInfoDoesNotInterruptAWarningAlreadySpeaking() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("warn-1", Level.WARNING)))
        assertEquals("warn-1", rules.speakingNow?.utteranceId)

        val decision = rules.submit(SpeechItem(request("ask-1", Level.INFO), userRequested = true))

        assertEquals(Decision.QUEUED, decision)
        assertEquals("warn-1", rules.speakingNow?.utteranceId)
    }

    // ---- Re-queue interrupted user-requested or navigation speech; drop Info ----

    @Test
    fun navigationSpeechIsRequeuedAfterBeingFlushedByACritical() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("nav-1", Level.WARNING, source = Source.NAVIGATION)))
        assertEquals("nav-1", rules.speakingNow?.utteranceId)

        rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))

        assertEquals("crit-1", rules.speakingNow?.utteranceId)
        assertEquals(listOf("nav-1"), rules.queuedRequests.map { it.utteranceId })

        rules.notifyFinished("crit-1")
        assertEquals("nav-1", rules.speakingNow?.utteranceId)
    }

    @Test
    fun userRequestedSpeechIsRequeuedAfterBeingFlushedByACritical() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("ask-1", Level.INFO), userRequested = true))
        assertEquals("ask-1", rules.speakingNow?.utteranceId)

        rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))
        rules.notifyFinished("crit-1")

        assertEquals("ask-1", rules.speakingNow?.utteranceId)
    }

    // ---- Two Criticals ----

    @Test
    fun secondCriticalFollowsFirstWithoutWaitingForAnythingElse() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))
        rules.submit(SpeechItem(request("warn-1", Level.WARNING)))
        val decision = rules.submit(SpeechItem(request("crit-2", Level.CRITICAL)))

        assertEquals(Decision.QUEUED, decision)
        // Critical jumps ahead of the already-queued Warning.
        assertEquals(listOf("crit-2", "warn-1"), rules.queuedRequests.map { it.utteranceId })

        rules.notifyFinished("crit-1")
        assertEquals("crit-2", rules.speakingNow?.utteranceId)
    }

    // ---- SOS countdown holds everything else ----

    @Test
    fun sosHoldsEveryLevelExceptTheCountdown() {
        rules.updateContext(sosActive = true)

        val critDecision = rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))
        val warnDecision = rules.submit(SpeechItem(request("warn-1", Level.WARNING)))
        val infoDecision = rules.submit(SpeechItem(request("info-1", Level.INFO), userRequested = true))

        assertEquals(Decision.HELD, critDecision)
        assertEquals(Decision.HELD, warnDecision)
        assertEquals(Decision.HELD, infoDecision)
        assertNull(rules.speakingNow)

        val countdown = rules.submit(
            SpeechItem(request("sos-1", Level.CRITICAL, source = Source.SYSTEM), sosCountdown = true)
        )

        assertEquals(Decision.SPOKEN, countdown)
        assertEquals("sos-1", rules.speakingNow?.utteranceId)
    }

    @Test
    fun heldItemsResumeOnceSosEnds() {
        rules.updateContext(sosActive = true)
        rules.submit(SpeechItem(request("crit-1", Level.CRITICAL)))
        rules.submit(SpeechItem(request("sos-1", Level.CRITICAL), sosCountdown = true))
        assertEquals("sos-1", rules.speakingNow?.utteranceId)

        rules.notifyFinished("sos-1")
        rules.updateContext(sosActive = false)

        assertEquals("crit-1", rules.speakingNow?.utteranceId)
    }

    // ---- Queue never exceeds 3; lowest priority is dropped on overflow ----

    @Test
    fun queueOverflowDropsTheLowestPriorityItem() {
        goIdleAndStationary()
        rules.submit(SpeechItem(request("speaking", Level.INFO)))
        rules.submit(SpeechItem(request("w1", Level.WARNING)))
        rules.submit(SpeechItem(request("w2", Level.WARNING)))
        rules.submit(SpeechItem(request("w3", Level.WARNING)))

        val decision = rules.submit(SpeechItem(request("info-low", Level.INFO), userRequested = true))

        assertEquals(Decision.DROPPED, decision)
        assertEquals(listOf("w1", "w2", "w3"), rules.queuedRequests.map { it.utteranceId })
    }

    @Test
    fun higherPriorityArrivalEvictsTheLowestQueuedItemInstead() {
        goIdleAndStationary()
        // A Critical is speaking, so nothing below can flush it (the "two Criticals" rule is the
        // only thing that can still cut in) -- this lets the queue fill up around it.
        rules.submit(SpeechItem(request("speaking-crit", Level.CRITICAL)))
        rules.submit(SpeechItem(request("w1", Level.WARNING)))
        rules.submit(SpeechItem(request("w2", Level.WARNING)))
        rules.submit(SpeechItem(request("i-low", Level.INFO), userRequested = true))
        assertEquals(listOf("w1", "w2", "i-low"), rules.queuedRequests.map { it.utteranceId })

        val decision = rules.submit(SpeechItem(request("w3", Level.WARNING)))

        assertEquals(Decision.QUEUED, decision)
        assertEquals(listOf("w1", "w2", "w3"), rules.queuedRequests.map { it.utteranceId })
    }
}
