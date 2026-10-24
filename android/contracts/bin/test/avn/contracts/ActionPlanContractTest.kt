package avn.contracts

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlanContractTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures")

    private fun files(dir: String): List<File> =
        File(fixtures, dir).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private val untrustedDir = File(System.getProperty("avn.repoRoot"), "tests/fixtures/plans/untrusted")

    private fun untrusted(name: String) = ActionPlanValidator.parse(File(untrustedDir, name).readText())

    @Test
    fun whitelistHasTheElevenSpecSteps() {
        val expected = setOf(
            "launch_app", "wait", "read_screen", "summarize", "scroll", "back",
            "tap_label", "navigate", "sos", "describe", "ask"
        )
        assertEquals(expected, ActionPlanValidator.allowedSteps)
    }

    @Test
    fun validFixturesRoundTripAndPassValidatorUnchanged() {
        val files = files("plans/valid")
        assertTrue("no plan fixtures in $fixtures/plans/valid", files.isNotEmpty())
        for (file in files) {
            val plan = Json.decodeFromString(ActionPlan.serializer(), file.readText())
            val again = Json.decodeFromString(
                ActionPlan.serializer(),
                Json.encodeToString(ActionPlan.serializer(), plan)
            )
            assertEquals(file.name, plan, again)

            val result = ActionPlanValidator.parse(file.readText())
            assertEquals(file.name, plan, result.plan)
            assertTrue("${file.name}: dropped ${result.droppedSteps}", result.droppedSteps.isEmpty())
            assertFalse("${file.name}: validator had to force confirmation", result.forcedConfirmation)
        }
    }

    @Test
    fun everyWhitelistedStepAppearsInSomeFixture() {
        val used = files("plans/valid")
            .flatMap { ActionPlanValidator.parse(it.readText()).plan.steps }
            .map { PlanStep.serializer().descriptor.getElementName(it.step.ordinal) }
            .toSet()
        assertEquals(ActionPlanValidator.allowedSteps, used)
    }

    @Test
    fun sosIsNeverForcedToAskConfirmation() {
        val result = ActionPlanValidator.parse(File(fixtures, "plans/valid/sos.json").readText())
        assertFalse(result.plan.requiresConfirmation)
        assertEquals(1, result.plan.steps.size)
    }

    @Test
    fun unknownStepIsDroppedAndOthersKept() {
        val result = untrusted("unknown_step.json")
        assertEquals(listOf("delete_all_chats"), result.droppedSteps)
        assertEquals(2, result.plan.steps.size)
    }

    @Test
    fun planWithOnlyUnknownStepsBecomesClarification() {
        val result = untrusted("all_unknown.json")
        assertTrue(result.plan.steps.isEmpty())
        assertEquals(ActionPlanValidator.DEFAULT_CLARIFICATION, result.plan.clarification)
    }

    @Test
    fun riskyTapLabelForcesConfirmation() {
        val result = untrusted("tap_send_unconfirmed.json")
        assertTrue(result.plan.requiresConfirmation)
        assertTrue(result.forcedConfirmation)
    }

    @Test
    fun garbageInputNeverThrows() {
        for (raw in listOf("", "not json", "[]", "null", """{"steps":"x"}""", """{"steps":[1,{"step":5}]}""")) {
            val result = ActionPlanValidator.parse(raw)
            assertTrue(raw, result.plan.steps.isEmpty())
            assertTrue(raw, !result.plan.clarification.isNullOrBlank())
        }
    }

    @Test
    fun existingIntentOkEnvelopePassesValidator() {
        val env = File(fixtures, "api/intent.ok.json").readText()
        val data = Json.parseToJsonElement(env).jsonObject.getValue("data")
        val result = ActionPlanValidator.parse(data.toString())
        assertEquals(3, result.plan.steps.size)
        assertTrue(result.droppedSteps.isEmpty())
    }

    @Test
    fun riskyLabelDetection() {
        assertTrue(ActionPlanValidator.isRiskyLabel("Send"))
        assertTrue(ActionPlanValidator.isRiskyLabel("Delete chat"))
        assertTrue(ActionPlanValidator.isRiskyLabel("إرسال"))
        assertFalse(ActionPlanValidator.isRiskyLabel("Search"))
        assertFalse(ActionPlanValidator.isRiskyLabel(null))
    }
}
