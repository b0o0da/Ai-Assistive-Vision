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
            "What note is this?" to LocalCommand.READ_CURRENCY,
            "who is this" to LocalCommand.RECOGNIZE_FACE,
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
        assertEquals(ParsedIntent.NoMatch, parser.parse(question, ParseContext(cameraActive = false)))
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
            assertEquals("\"$phrase\" should not match a rule", ParsedIntent.NoMatch, parser.parse(phrase))
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

    private fun plansOf(table: Iterable<String>, check: (String, ActionPlan) -> Unit) {
        for (phrase in table) check(phrase, planOf(phrase))
    }

    @Test
    fun unknownTextIsAnExplicitNoMatch() {
        assertEquals(ParsedIntent.NoMatch, parser.parse("tell me a joke"))
        assertEquals(ParsedIntent.NoMatch, parser.parse(""))
        assertEquals(ParsedIntent.NoMatch, parser.parse("   "))
    }

    @Test
    fun faceCommandsAreLocal() {
        val phrases = phraseList("Who is this?|who is that|who is in front of me|do you know this person")
        for (phrase in phrases) {
            assertEquals(phrase, ParsedIntent.Local(LocalCommand.RECOGNIZE_FACE), parser.parse(phrase))
        }
        assertEquals(ParsedIntent.Local(LocalCommand.ENROLL_PERSON, "Sara"), parser.parse("enroll sara"))
    }

    @Test
    fun currencyCommandsAreLocal() {
        val phrases = phraseList("What note is this?|how much is this bill|identify the money|read the currency")
        for (phrase in phrases) {
            assertEquals(phrase, ParsedIntent.Local(LocalCommand.READ_CURRENCY), parser.parse(phrase))
        }
        assertEquals(ParsedIntent.Local(LocalCommand.READ_TEXT), parser.parse("read this"))
    }

    @Test
    fun whoIsThatIsAFaceQuestionNotASceneQuestion() {
        assertEquals(ParsedIntent.Local(LocalCommand.RECOGNIZE_FACE), parser.parse("who is that"))
        assertEquals(listOf(PlanStep.ASK to "What is that?"), stepsOf("What is that?"))
    }

    @Test
    fun aCompoundOrRiskyOpenRequestIsNotALaunch() {
        val phrases = phraseList(
            "open whatsapp and send hi to mom|open whatsapp and reply yes|launch chrome and delete history",
            "open the pay app|open the app that my friend sent me yesterday"
        )
        for (phrase in phrases) {
            assertEquals("\"$phrase\"", ParsedIntent.NoMatch, parser.parse(phrase))
        }
        assertEquals(listOf(PlanStep.LAUNCH_APP to "Google Maps"), stepsOf("open google maps"))
    }

    @Test
    fun thereAreNoSendOrReplyIntents() {
        val phrases = phraseList(
            "send a message to mom|send hi|reply to john|reply yes|reply to this message|forward this message",
            "message my brother|text sara I am late|call mom|post this on facebook"
        )
        for (phrase in phrases) {
            val parsed = parser.parse(phrase)
            assertTrue("\"$phrase\" gave $parsed", parsed == ParsedIntent.NoMatch)
        }
    }

    @Test
    fun everyEmergencyPhraseRoutesToSosInAnyCaseAndWithPunctuation() {
        for (phrase in EMERGENCY) {
            for (variant in listOf(phrase, phrase.uppercase(), "$phrase!", "Hey, $phrase please", "  $phrase  ")) {
                assertTrue("\"$variant\"", isSos(variant))
            }
        }
    }

    @Test
    fun everyListedPhraseMapsToItsIntent() {
        plansOf(DESCRIBE) { p, plan -> assertEquals(p, listOf(PlanStep.DESCRIBE), plan.steps.map { it.step }) }
        plansOf(READ_SCREEN) { p, plan -> assertEquals(p, listOf(PlanStep.READ_SCREEN), plan.steps.map { it.step }) }
        plansOf(SUMMARIZE) { p, plan -> assertEquals(p, listOf(PlanStep.SUMMARIZE), plan.steps.map { it.step }) }
        plansOf(BACK) { p, plan -> assertEquals(p, listOf(PlanStep.BACK), plan.steps.map { it.step }) }
        val readMessages = listOf(PlanStep.LAUNCH_APP, PlanStep.WAIT, PlanStep.READ_SCREEN)
        plansOf(READ_MESSAGES) { p, plan -> assertEquals(p, readMessages, plan.steps.map { it.step }) }
        plansOf(SCROLL.keys) { p, plan ->
            assertEquals(p, listOf(PlanStep.SCROLL to SCROLL[p]), plan.steps.map { it.step to it.arg })
        }
        for ((phrase, command) in LOCAL_EXACT) {
            assertEquals(phrase, ParsedIntent.Local(command), parser.parse(phrase))
        }
        for (starter in ASK_STARTERS) {
            val question = "$starter something here"
            assertEquals(question, listOf(PlanStep.ASK), planOf(question).steps.map { it.step })
            assertEquals(question, ParsedIntent.NoMatch, parser.parse(question, ParseContext(cameraActive = false)))
        }
    }

    @Test
    fun noPhraseBelongsToTwoIntents() {
        val plans = listOf(EMERGENCY, DESCRIBE, READ_SCREEN, SUMMARIZE)
        val others = listOf(BACK, READ_MESSAGES, SCROLL.keys, LOCAL_EXACT.keys)
        val tables = plans + others
        val total = tables.sumOf { it.size }
        assertEquals(total, tables.flatten().toSet().size)
    }
}
