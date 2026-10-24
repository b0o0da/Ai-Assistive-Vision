package avn.app.voice

import avn.contracts.ActionPlan
import avn.contracts.PlanStep
import avn.contracts.PlanStepItem
import avn.contracts.Transcript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIntentRouterTest {
    private class CountingCloud(private val result: () -> ActionPlan?) : CloudIntent {
        var calls = 0

        override suspend fun plan(transcript: Transcript): ActionPlan? {
            calls++
            return result()
        }
    }

    private val local = FakeLocalCommandHandler()

    private fun transcript(text: String) = Transcript(text, confidence = 1.0, language = "en")

    private fun route(text: String, cloud: CloudIntent? = null, online: Boolean = true): ActionPlan = runBlocking {
        RuleIntentRouter(local, cloud = cloud, isOnline = { online }).route(transcript(text))
    }

    @Test
    fun emergencyNeverCallsTheCloudAndWorksOffline() {
        val cloud = CountingCloud { null }
        val plan = route("SOS", cloud, online = false)
        assertEquals(listOf(PlanStep.SOS), plan.steps.map { it.step })
        assertEquals(0, cloud.calls)
    }

    @Test
    fun localCommandIsDispatchedAndTheReturnedPlanIsEmpty() {
        val plan = route("louder")
        assertEquals(listOf(LocalCommand.LOUDER to null), local.commands)
        assertTrue(plan.steps.isEmpty())
        assertNull(plan.clarification)
        assertNull(plan.spokenAck)
    }

    @Test
    fun rulesWinOverTheCloud() {
        val cloud = CountingCloud { null }
        route("open whatsapp", cloud)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun noRuleAndOfflineSpeaksTheOfflineMessage() {
        val cloud = CountingCloud { null }
        val plan = route("tell me a joke", cloud, online = false)
        assertEquals("I can't do that offline.", plan.clarification)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun noRuleAndNoCloudSpeaksTheContractFallback() {
        assertEquals("I didn't understand that. Please say it another way.", route("tell me a joke").clarification)
    }

    @Test
    fun cloudPlanIsValidatedBeforeItIsReturned() {
        val cloud = CountingCloud {
            ActionPlan(listOf(PlanStepItem(PlanStep.TAP_LABEL, "Send")), requiresConfirmation = false)
        }
        val plan = route("message my brother that I am late", cloud)
        assertEquals(1, cloud.calls)
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun emptyCloudPlanGetsAClarification() {
        val cloud = CountingCloud { ActionPlan(emptyList(), requiresConfirmation = false) }
        assertEquals(PlanValidator.NOT_UNDERSTOOD, route("do something odd", cloud).clarification)
    }

    @Test
    fun cloudFailureFallsBackInsteadOfThrowing() {
        val failing = CountingCloud { throw IllegalStateException("boom") }
        assertEquals(PlanValidator.NOT_UNDERSTOOD, route("do something odd", failing).clarification)
        val silent = CountingCloud { null }
        assertEquals(PlanValidator.NOT_UNDERSTOOD, route("do something odd", silent).clarification)
        assertFalse(route("do something odd", silent).requiresConfirmation)
    }
}
