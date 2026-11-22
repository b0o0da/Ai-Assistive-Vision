package avn.app.voice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    private var now = 1_000L

    private fun fake(onlyWhileStarted: Boolean = false) = FakeWakeWord(silentVoiceLog, { now++ }, onlyWhileStarted)

    @Test
    fun debugButtonEmitsExactlyOneEvent() = runBlocking {
        val wake = fake()
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        assertTrue(wake.trigger())
        yield()
        job.cancel()
        assertEquals(listOf(WakeEvent(1_000L, 1f)), events)
    }

    @Test
    fun scriptedTriggersArriveInOrderWithTheirConfidence() = runBlocking {
        val wake = fake()
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        wake.trigger(0.5f)
        wake.trigger(0.9f)
        yield()
        job.cancel()
        assertEquals(listOf(0.5f, 0.9f), events.map { it.confidence })
        assertTrue(events[0].timestampMs < events[1].timestampMs)
    }

    @Test
    fun nothingIsReplayedToALateSubscriber() = runBlocking {
        val wake = fake()
        wake.trigger()
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        yield()
        job.cancel()
        assertTrue(events.isEmpty())
    }

    @Test
    fun everySubscriberGetsTheEvent() = runBlocking {
        val wake = fake()
        val first = mutableListOf<WakeEvent>()
        val second = mutableListOf<WakeEvent>()
        val jobs = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(first) },
            launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(second) }
        )
        wake.trigger()
        yield()
        jobs.forEach { it.cancel() }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun startAndStopAreTracked() {
        val wake: WakeWord = fake()
        val fakeWake = wake as FakeWakeWord
        assertFalse(fakeWake.isRunning)
        wake.start()
        assertTrue(fakeWake.isRunning)
        wake.stop()
        assertFalse(fakeWake.isRunning)
    }

    @Test
    fun triggerWorksBeforeStartByDefault() = runBlocking {
        val wake = fake()
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        assertTrue(wake.trigger())
        yield()
        job.cancel()
        assertEquals(1, events.size)
    }

    @Test
    fun strictModeIgnoresTriggersUntilStarted() = runBlocking {
        val wake = fake(onlyWhileStarted = true)
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        assertFalse(wake.trigger())
        wake.start()
        assertTrue(wake.trigger())
        wake.stop()
        assertFalse(wake.trigger())
        yield()
        job.cancel()
        assertEquals(1, events.size)
    }

    @Test
    fun aBurstNeverBlocksAndKeepsTheNewestEvents() = runBlocking {
        val wake = fake()
        val events = mutableListOf<WakeEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.wakes.toList(events) }
        repeat(40) { wake.trigger() }
        yield()
        job.cancel()
        assertTrue(events.size in 1..40)
        assertEquals(1_039L, events.last().timestampMs)
    }
}
