package avn.app.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosCountdownStateTest {
    @Test
    fun triggerStartsACountdownFromIdle() {
        val state = SosCountdownState()
        assertTrue(state.trigger())
        assertEquals(SosCountdownState.State.COUNTING_DOWN, state.state)
    }

    @Test
    fun secondTriggerWhileCountingDownIsIgnored() {
        val state = SosCountdownState()
        assertTrue(state.trigger())
        assertFalse(state.trigger())
        assertEquals(SosCountdownState.State.COUNTING_DOWN, state.state)
    }

    @Test
    fun cancelOnlyWorksMidCountdown() {
        val neverStarted = SosCountdownState()
        assertFalse(neverStarted.cancel())

        val running = SosCountdownState()
        running.trigger()
        assertTrue(running.cancel())
        assertEquals(SosCountdownState.State.CANCELLED, running.state)
        assertFalse(running.cancel())
    }

    @Test
    fun markSentOnlyWorksMidCountdownAndCantHappenTwice() {
        val state = SosCountdownState()
        assertFalse(state.markSent())

        state.trigger()
        assertTrue(state.markSent())
        assertEquals(SosCountdownState.State.SENT, state.state)
        assertFalse(state.markSent())
    }

    @Test
    fun cancelledCountdownCannotThenBeMarkedSent() {
        val state = SosCountdownState()
        state.trigger()
        state.cancel()
        assertFalse(state.markSent())
    }

    @Test
    fun resetReturnsToIdleFromAnyState() {
        val afterSend = SosCountdownState().apply { trigger(); markSent(); reset() }
        assertEquals(SosCountdownState.State.IDLE, afterSend.state)
        assertTrue(afterSend.trigger())

        val afterCancel = SosCountdownState().apply { trigger(); cancel(); reset() }
        assertEquals(SosCountdownState.State.IDLE, afterCancel.state)
        assertTrue(afterCancel.trigger())
    }
}
