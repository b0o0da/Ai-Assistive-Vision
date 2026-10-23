package avn.app.voice

import avn.contracts.ActionPlan
import avn.contracts.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIntentParserTest {
    private val parser = RuleIntentParser()

    private fun phraseList(vararg lines: String): List<String> = lines.flatMap { it.split("|") }

    private fun planOf(text: String, context: ParseContext = ParseContext()): ActionPlan {
        val parsed = parser.parse(text, context)
        assertTrue("expected a plan for \"$text\" but got $parsed", parsed is ParsedIntent.Plan)
        return (parsed as ParsedIntent.Plan).plan
    }

    private fun stepsOf(text: String, context: ParseContext = ParseContext()): List<Pair<PlanStep, String?>> =
        planOf(text, context).steps.map { it.step to it.arg }

    private fun isSos(text: String): Boolean {
        val parsed = parser.parse(text) as? ParsedIntent.Plan ?: return false
        return parsed.plan.steps.any { it.step == PlanStep.SOS }
    }

    @Test
    fun emergencyPhrasesAlwaysRouteToSos() {
        val phrases = phraseList(
            "SOS|sos!|S.O.S.|Help!|help me|I need help|This is an emergency!",
            "Hey, call for help please|I'm in danger|EMERGENCY|send help|Help me now"
        )
        for (phrase in phrases) {
            assertEquals(phrase, listOf(PlanStep.SOS to null), stepsOf(phrase))
            assertFalse(phrase, planOf(phrase).requiresConfirmation)
        }
    }

    @Test
    fun emergencyNearMissesAreNotSos() {
        val phrases = phraseList(
            "help me find my keys|I need help finding the door|emergency exit|read the sos message|helpful|helper"
        )
        for (phrase in phrases) {
            assertFalse(phrase, isSos(phrase))
        }
    }

    @Test
    fun describeCommands() {
        for (phrase in phraseList("What's around me?|what is around me|Describe the scene|what do you see")) {
            assertEquals(phrase, listOf(PlanStep.DESCRIBE to null), stepsOf(phrase))
        }
    }

    @Test
    fun openAppMatchesTheContractFixture() {
        val plan = planOf("Open WhatsApp and read my new messages")
        assertEquals(
            listOf(PlanStep.LAUNCH_APP to "WhatsApp", PlanStep.WAIT to null, PlanStep.READ_SCREEN to null),
            plan.steps.map { it.step to it.arg }
        )
        assertEquals("Opening WhatsApp.", plan.spokenAck)
        assertFalse(plan.requiresConfirmation)
        assertEquals(plan, planOf("read my new messages"))
    }

    @Test
    fun openAppVariants() {
        assertEquals(listOf(PlanStep.LAUNCH_APP to "WhatsApp"), stepsOf("open whatsapp"))
        assertEquals(listOf(PlanStep.LAUNCH_APP to "Facebook"), stepsOf("Launch Facebook"))
        assertEquals(listOf(PlanStep.LAUNCH_APP to "Google Maps"), stepsOf("open google maps"))
        assertEquals(listOf(PlanStep.LAUNCH_APP to "Calculator"), stepsOf("open the calculator"))
        assertEquals("Opening Chrome.", planOf("Please open chrome for me").spokenAck)
    }

    @Test
    fun openWithoutAnAppAsksWhich() {
        val plan = planOf("open")
        assertTrue(plan.steps.isEmpty())
        assertEquals("Which app do you want to open?", plan.clarification)
    }

    @Test
    fun screenCommands() {
        assertEquals(listOf(PlanStep.READ_SCREEN to null), stepsOf("read the screen"))
        assertEquals(listOf(PlanStep.SUMMARIZE to null), stepsOf("summarize these"))
        assertEquals(listOf(PlanStep.SCROLL to "down"), stepsOf("Scroll down"))
        assertEquals(listOf(PlanStep.SCROLL to "up"), stepsOf("can you scroll up"))
        assertEquals(listOf(PlanStep.BACK to null), stepsOf("go back"))
        assertEquals("Scrolling down.", planOf("scroll down").spokenAck)
    }

    @Test
    fun scrollWithoutDirectionAsks() {
        assertEquals("Up or down?", planOf("scroll").clarification)
    }

    @Test
    fun navigationCommands() {
        assertEquals(listOf(PlanStep.NAVIGATE to "nearest pharmacy"), stepsOf("Take me to the nearest pharmacy"))
        assertEquals(listOf(PlanStep.NAVIGATE to "tahrir square"), stepsOf("navigate to Tahrir Square"))
        assertEquals(listOf(PlanStep.NAVIGATE to "home"), stepsOf("take me home"))
        assertEquals("Getting directions to Tahrir Square.", planOf("directions to tahrir square").spokenAck)
        assertEquals("Where do you want to go?", planOf("take me").clarification)
    }

    @Test
    fun localCommands() {
        val expected = mapOf(
            "stop" to LocalCommand.STOP,
            "Stop navigation" to LocalCommand.STOP_NAVIGATION,
            "cancel" to LocalCommand.CANCEL,
            "never mind" to LocalCommand.CANCEL,
            "repeat that" to LocalCommand.REPEAT,
            "louder" to LocalCommand.LOUDER,
            "speak slower" to LocalCommand.SLOWER,
            "read this" to LocalCommand.READ_TEXT,
            "What does this say?" to LocalCommand.READ_TEXT
        )
        for ((phrase, command) in expected) {
            assertEquals(phrase, ParsedIntent.Local(command), parser.parse(phrase))
        }
        assertEquals(ParsedIntent.Local(LocalCommand.ENROLL_PERSON, "Sara Ali"), parser.parse("enroll sara ali"))
        assertEquals("Who should I enroll?", planOf("enroll").clarification)
    }

    @Test
    fun askAboutTheSceneNeedsTheCamera() {
        val question = "Is there a chair in front of me?"
        assertEquals(listOf(PlanStep.ASK to question), stepsOf(question))
        assertEquals(listOf(PlanStep.ASK to "how many people are here"), stepsOf("how many people are here "))
        assertNull(parser.parse(question, ParseContext(cameraActive = false)))
    }

    @Test
    fun tapLabelFlagsRiskyLabels() {
        val safe = planOf("tap settings")
        assertEquals(listOf(PlanStep.TAP_LABEL to "settings"), safe.steps.map { it.step to it.arg })
        assertEquals("Tapping settings.", safe.spokenAck)
        assertFalse(safe.requiresConfirmation)

        for (phrase in phraseList("press send|tap the Send button|click delete|tap pay now|tap confirm")) {
            val plan = planOf(phrase)
            assertTrue(phrase, plan.requiresConfirmation)
            assertNull(phrase, plan.spokenAck)
        }
    }

    @Test
    fun fillerWordsAreIgnored() {
        assertEquals(stepsOf("open whatsapp"), stepsOf("Hey, could you please open WhatsApp for me"))
        assertEquals(stepsOf("scroll down"), stepsOf("Okay scroll down please"))
        assertEquals(stepsOf("what's around me"), stepsOf("Hey, what's around me please"))
    }

    @Test
    fun nearMissesAndUnknownTextMatchNothing() {
        val phrases = phraseList(
            "|   |tell me a joke|blah blah|what time is it|stop the car|cancel my order",
            "back to the future|scroll through photos|read this book to me|navigate|please"
        )
        for (phrase in phrases) {
            assertNull("\"$phrase\" should not match a rule", parser.parse(phrase))
        }
    }

    @Test
    fun riskyLabelDetection() {
        assertTrue(RiskyLabels.isRisky("Send"))
        assertTrue(RiskyLabels.isRisky("delete message"))
        assertTrue(RiskyLabels.isRisky("إرسال"))
        assertFalse(RiskyLabels.isRisky("settings"))
        assertFalse(RiskyLabels.isRisky("sender name"))
    }
}
