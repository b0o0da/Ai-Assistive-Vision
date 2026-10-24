package avn.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationCandidateFilterTest {
    @Test
    fun rejectsTooShortText() {
        assertFalse(DestinationCandidateFilter.isPlausibleDestination(""))
        assertFalse(DestinationCandidateFilter.isPlausibleDestination("A"))
        assertFalse(DestinationCandidateFilter.isPlausibleDestination("12"))
    }

    @Test
    fun rejectsTextWithNoLetters() {
        assertFalse(DestinationCandidateFilter.isPlausibleDestination("123"))
        assertFalse(DestinationCandidateFilter.isPlausibleDestination("---"))
    }

    @Test
    fun acceptsPlausiblePlaceNames() {
        assertTrue(DestinationCandidateFilter.isPlausibleDestination("Pharmacy"))
        assertTrue(DestinationCandidateFilter.isPlausibleDestination("7-Eleven"))
    }

    @Test
    fun trimsBeforeCheckingLength() {
        assertFalse(DestinationCandidateFilter.isPlausibleDestination("  A  "))
        assertTrue(DestinationCandidateFilter.isPlausibleDestination("  Gym  "))
    }
}

class OcrDestinationConfirmStateTest {
    @Test
    fun proposeStartsPendingConfirmationFromIdle() {
        val state = OcrDestinationConfirmState()
        assertTrue(state.propose("Pharmacy", nowSeconds = 0.0))
        assertEquals(OcrDestinationConfirmState.State.PENDING_CONFIRMATION, state.state)
        assertEquals("Pharmacy", state.pendingText)
    }

    @Test
    fun secondProposalWhilePendingIsIgnored() {
        val state = OcrDestinationConfirmState()
        assertTrue(state.propose("Pharmacy", nowSeconds = 0.0))
        assertFalse(state.propose("Gym", nowSeconds = 0.5))
        assertEquals("Pharmacy", state.pendingText)
    }

    @Test
    fun confirmReturnsPendingTextAndTransitionsToConfirmed() {
        val state = OcrDestinationConfirmState()
        state.propose("Pharmacy", nowSeconds = 0.0)
        assertEquals("Pharmacy", state.confirm(nowSeconds = 1.0))
        assertEquals(OcrDestinationConfirmState.State.CONFIRMED, state.state)
    }

    @Test
    fun confirmWithNothingPendingReturnsNull() {
        val state = OcrDestinationConfirmState()
        assertNull(state.confirm(nowSeconds = 0.0))
    }

    @Test
    fun dismissReturnsToIdleAndClearsPendingText() {
        val state = OcrDestinationConfirmState()
        state.propose("Pharmacy", nowSeconds = 0.0)
        assertTrue(state.dismiss(nowSeconds = 1.0))
        assertEquals(OcrDestinationConfirmState.State.IDLE, state.state)
        assertNull(state.pendingText)
    }

    @Test
    fun dismissWithNothingPendingIsANoOp() {
        val state = OcrDestinationConfirmState()
        assertFalse(state.dismiss(nowSeconds = 0.0))
    }

    @Test
    fun cooldownBlocksRepromptOfSameTextWithinWindow() {
        val state = OcrDestinationConfirmState(repromptCooldownSeconds = 10.0)
        state.propose("Pharmacy", nowSeconds = 0.0)
        state.confirm(nowSeconds = 0.0)
        state.reset()
        assertFalse(state.propose("Pharmacy", nowSeconds = 5.0))
        assertEquals(OcrDestinationConfirmState.State.IDLE, state.state)
    }

    @Test
    fun cooldownExpiresAllowingRepromptOfSameTextAfterWindow() {
        val state = OcrDestinationConfirmState(repromptCooldownSeconds = 10.0)
        state.propose("Pharmacy", nowSeconds = 0.0)
        state.confirm(nowSeconds = 0.0)
        state.reset()
        assertTrue(state.propose("Pharmacy", nowSeconds = 10.0))
    }

    @Test
    fun cooldownAfterDismissAlsoAppliesToSameText() {
        val state = OcrDestinationConfirmState(repromptCooldownSeconds = 10.0)
        state.propose("Pharmacy", nowSeconds = 0.0)
        state.dismiss(nowSeconds = 0.0)
        assertFalse(state.propose("Pharmacy", nowSeconds = 5.0))
    }

    @Test
    fun cooldownDoesNotBlockDifferentText() {
        val state = OcrDestinationConfirmState(repromptCooldownSeconds = 10.0)
        state.propose("Pharmacy", nowSeconds = 0.0)
        state.confirm(nowSeconds = 0.0)
        state.reset()
        assertTrue(state.propose("Gym", nowSeconds = 0.1))
    }

    @Test
    fun resetReturnsToIdleFromAnyState() {
        val afterConfirm = OcrDestinationConfirmState().apply {
            propose("Pharmacy", nowSeconds = 0.0)
            confirm(nowSeconds = 0.0)
            reset()
        }
        assertEquals(OcrDestinationConfirmState.State.IDLE, afterConfirm.state)
        assertNull(afterConfirm.pendingText)

        val afterDismiss = OcrDestinationConfirmState().apply {
            propose("Pharmacy", nowSeconds = 0.0)
            dismiss(nowSeconds = 0.0)
            reset()
        }
        assertEquals(OcrDestinationConfirmState.State.IDLE, afterDismiss.state)
    }
}
