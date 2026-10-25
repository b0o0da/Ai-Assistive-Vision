package avn.engine

import avn.contracts.Level
import avn.contracts.Source
import avn.contracts.SpeechRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AVN-56 (WS-16): [SpeechQueue]'s three done-when invariants -- depth-limited overflow drops the
 * lowest-priority item first, Critical is never the one dropped, and expired items are never
 * returned -- plus the ordering rule (level, then risk, then age) those invariants depend on.
 */
class SpeechQueueTest {
    private fun request(id: String, level: Level) = SpeechRequest(
        utteranceId = id,
        text = id,
        level = level,
        source = Source.CAMERA,
        interruptible = true
    )

    // ---- The config file this ticket also delivers ----

    @Test
    fun configLoadsTheRealQueueJsonResource() {
        val config = SpeechQueueConfig.load()
        assertEquals(3, config.maxDepth)
        assertEquals(3000L, config.warningExpiryMs)
        assertEquals(10_000L, config.infoExpiryMs)
    }

    // ---- Order: level, then risk, then age ----

    @Test
    fun peekAllOrdersCriticalBeforeWarningBeforeInfoRegardlessOfEnqueueOrder() {
        val clock = FakeClock()
        val queue = SpeechQueue(clock)
        queue.enqueue(request("info", Level.INFO))
        clock.advanceBy(1)
        queue.enqueue(request("warning", Level.WARNING))
        clock.advanceBy(1)
        queue.enqueue(request("critical", Level.CRITICAL))

        assertEquals(listOf("critical", "warning", "info"), queue.peekAll().map { it.utteranceId })
    }

    @Test
    fun peekAllOrdersHigherRiskFirstWithinTheSameLevel() {
        val clock = FakeClock()
        val queue = SpeechQueue(clock)
        queue.enqueue(request("low", Level.WARNING), risk = 10)
        clock.advanceBy(1)
        queue.enqueue(request("high", Level.WARNING), risk = 90)

        assertEquals(listOf("high", "low"), queue.peekAll().map { it.utteranceId })
    }

    @Test
    fun peekAllOrdersOlderFirstWhenLevelAndRiskAreEqual() {
        val clock = FakeClock()
        val queue = SpeechQueue(clock)
        queue.enqueue(request("first", Level.INFO), risk = 50)
        clock.advanceBy(5)
        queue.enqueue(request("second", Level.INFO), risk = 50)

        assertEquals(listOf("first", "second"), queue.peekAll().map { it.utteranceId })
    }

    // ---- Done when: depth limit 3 drops lowest priority first ----

    @Test
    fun overflowDropsTheLowestPriorityItem() {
        val decisions = mutableListOf<QueueDecision>()
        val clock = FakeClock()
        val queue = SpeechQueue(clock, onDecision = { decisions += it })
        queue.enqueue(request("warning-90", Level.WARNING), risk = 90)
        clock.advanceBy(1)
        queue.enqueue(request("warning-50", Level.WARNING), risk = 50)
        clock.advanceBy(1)
        queue.enqueue(request("warning-10", Level.WARNING), risk = 10)
        clock.advanceBy(1)
        // Depth is already 3; a Critical outranks all three Warnings, so the lowest-priority
        // Warning (risk 10) must be the one dropped to make room.
        queue.enqueue(request("critical", Level.CRITICAL), risk = 1)

        assertEquals(
            listOf("critical", "warning-90", "warning-50"),
            queue.peekAll().map { it.utteranceId }
        )
        assertEquals("queue_overflow", decisions.single().reason)
        assertEquals("warning-10", decisions.single().request.utteranceId)
    }

    // ---- Done when: Critical never dropped ----

    @Test
    fun overflowNeverDropsACriticalEvenWhenAllThreeSlotsAreAlreadyCritical() {
        val decisions = mutableListOf<QueueDecision>()
        val clock = FakeClock()
        val queue = SpeechQueue(clock, onDecision = { decisions += it })
        queue.enqueue(request("critical-90", Level.CRITICAL), risk = 90)
        clock.advanceBy(1)
        queue.enqueue(request("critical-50", Level.CRITICAL), risk = 50)
        clock.advanceBy(1)
        // Technically the lowest-priority entry of the three (lowest risk) once a 4th arrives --
        // must still survive, because it is Critical.
        queue.enqueue(request("critical-10", Level.CRITICAL), risk = 10)
        clock.advanceBy(1)
        queue.enqueue(request("warning", Level.WARNING), risk = 99)

        assertEquals(
            listOf("critical-90", "critical-50", "critical-10"),
            queue.peekAll().map { it.utteranceId }
        )
        assertEquals("queue_overflow", decisions.single().reason)
        assertEquals("warning", decisions.single().request.utteranceId)
    }

    // ---- Done when: expired items never spoken ----

    @Test
    fun expiredWarningIsPurgedAndNeverReturned() {
        val decisions = mutableListOf<QueueDecision>()
        val clock = FakeClock()
        val queue = SpeechQueue(clock, onDecision = { decisions += it })
        queue.enqueue(request("warning", Level.WARNING))
        clock.advanceBy(3000) // config's Warning expiry -- "about 3 s"

        assertNull(queue.dequeueNext())
        assertEquals("expired", decisions.single().reason)
        assertEquals("warning", decisions.single().request.utteranceId)
    }

    @Test
    fun liveItemSurvivesWhileAnExpiredSiblingIsPurgedFirst() {
        val clock = FakeClock()
        val queue = SpeechQueue(clock)
        queue.enqueue(request("warning", Level.WARNING)) // expires at 3000ms
        queue.enqueue(request("info", Level.INFO)) // expires at 10000ms
        clock.advanceBy(3500) // warning now stale, info still well within budget

        assertEquals("info", queue.dequeueNext()?.utteranceId)
        assertNull(queue.dequeueNext())
    }

    @Test
    fun criticalNeverExpiresEvenAfterALongWait() {
        val clock = FakeClock()
        val queue = SpeechQueue(clock)
        queue.enqueue(request("critical", Level.CRITICAL))
        clock.advanceBy(60_000)

        assertEquals("critical", queue.dequeueNext()?.utteranceId)
    }
}
