package avn.app.voice

import avn.contracts.ActionPlan
import avn.contracts.PlanStep
import avn.contracts.PlanStepItem

data class ParseContext(val cameraActive: Boolean = true)

sealed class ParsedIntent {
    data class Plan(val plan: ActionPlan) : ParsedIntent()

    data class Local(val command: LocalCommand, val arg: String? = null) : ParsedIntent()
}

private fun phraseList(vararg lines: String): List<String> = lines.flatMap { it.split("|") }

private fun phraseMap(vararg lines: String): Map<String, String> =
    phraseList(*lines).associate { it.substringBefore("=") to it.substringAfter("=") }

object RiskyLabels {
    private val words = phraseList(
        "send|delete|buy|pay|post|confirm",
        "ارسال|إرسال|حذف|شراء|دفع|نشر|تأكيد"
    ).toSet()

    fun isRisky(label: String): Boolean = normalizeText(label).split(" ").any { it in words }
}

private val NON_TEXT = Regex("[^\\p{L}\\p{N} ]")
private val SPACES = Regex("\\s+")
private val LEADING_FILLERS = phraseList("hey|okay|ok|please|could you|can you|would you|will you")
private val TRAILING_FILLERS = phraseList("please|for me")

internal fun normalizeText(text: String): String {
    var s = text.lowercase().replace("'", "").replace("\u2019", "")
    s = SPACES.replace(NON_TEXT.replace(s, " "), " ").trim()
    var changed = true
    while (changed) {
        changed = false
        for (filler in LEADING_FILLERS) {
            if (s.startsWith("$filler ")) {
                s = s.removePrefix("$filler ").trim()
                changed = true
            }
        }
        for (filler in TRAILING_FILLERS) {
            if (s.endsWith(" $filler")) {
                s = s.removeSuffix(" $filler").trim()
                changed = true
            }
        }
    }
    return s
}

private val EMERGENCY = phraseList(
    "sos|s o s|send sos|call sos|help|help me|help now|help me now|i need help|i need help now",
    "emergency|this is an emergency|its an emergency|call for help|send help|i am in danger|im in danger"
).toSet()

private fun commands(command: LocalCommand, vararg lines: String): List<Pair<String, LocalCommand>> =
    phraseList(*lines).map { it to command }

private val LOCAL_EXACT: Map<String, LocalCommand> = (
    commands(
        LocalCommand.STOP_NAVIGATION,
        "stop navigation|stop navigating|cancel navigation|end navigation|cancel route|stop directions"
    ) +
        commands(LocalCommand.STOP, "stop|stop talking|be quiet|quiet") +
        commands(LocalCommand.CANCEL, "cancel|never mind|nevermind") +
        commands(LocalCommand.REPEAT, "repeat|repeat that|say that again|say again|what did you say|come again") +
        commands(LocalCommand.LOUDER, "louder|speak louder|volume up|turn it up") +
        commands(LocalCommand.QUIETER, "quieter|softer|speak quieter|volume down|turn it down") +
        commands(LocalCommand.FASTER, "faster|speak faster|speed up") +
        commands(LocalCommand.SLOWER, "slower|speak slower|slow down") +
        commands(
            LocalCommand.READ_TEXT,
            "read this|read that|read the text|read text|read the sign|read this sign|read the label",
            "read the menu|what does this say|what does that say|what does it say"
        )
    ).toMap()

private val DESCRIBE = phraseList(
    "whats around me|what is around me|whats in front of me|what is in front of me|describe",
    "describe the scene|describe my surroundings|what do you see|whats going on|look around",
    "tell me whats around me"
).toSet()
private val READ_SCREEN = phraseList(
    "read the screen|read screen|read my screen|whats on the screen|what is on the screen",
    "whats on my screen|what is on my screen"
).toSet()
private val SUMMARIZE = phraseList(
    "summarize|summarize this|summarize these|summarise|summarise this|summarise these",
    "summarize the screen|summarize this screen"
).toSet()
private val BACK = phraseList("go back|back|previous screen|go to the previous screen").toSet()
private val READ_MESSAGES = phraseList(
    "read my new messages|read new messages|read my messages|read messages|check my messages"
).toSet()
private val SCROLL = phraseMap("scroll down=down|page down=down|scroll up=up|page up=up")
private val ASK_STARTERS = phraseList(
    "is there|are there|how many|what color|what colour|what is this|what is that|who is that",
    "is this|is that|which one"
)
private val KNOWN_APPS = phraseMap(
    "whatsapp=WhatsApp|facebook=Facebook|chrome=Chrome|youtube=YouTube|maps=Maps",
    "google maps=Google Maps|messages=Messages|camera=Camera"
)

private val OPEN_AND_READ = Regex("(?:open|launch) (.+?) and read .+")
private val OPEN = Regex("(?:open|launch) (.+)")
private val NAVIGATE = Regex("(?:take me to|navigate to|directions to|how do i get to|get me to|take me)(?: (.+))?")
private val TAP = Regex("(?:tap|click|press|select) (?:on )?(?:the )?(.+)")
private val ENROLL = Regex("enroll (.+)")

private fun titleCase(text: String): String {
    val words = text.split(" ").filter { it.isNotEmpty() }
    return words.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

private fun appName(raw: String): String {
    val name = raw.removePrefix("the ").trim()
    return KNOWN_APPS[name] ?: titleCase(name)
}

private fun step(step: PlanStep, arg: String? = null) = PlanStepItem(step, arg)

private fun plan(vararg steps: PlanStepItem, ack: String? = null, confirm: Boolean = false): ParsedIntent =
    ParsedIntent.Plan(ActionPlan(steps.toList(), confirm, ack))

private fun clarify(question: String): ParsedIntent = ParsedIntent.Plan(ActionPlan(emptyList(), false, null, question))

class RuleIntentParser {
    /** Null means no rule matched; the caller decides between the cloud `/intent` path and a spoken fallback. */
    fun parse(text: String, context: ParseContext = ParseContext()): ParsedIntent? {
        val s = normalizeText(text)
        if (s.isEmpty()) return null
        if (s in EMERGENCY) return plan(step(PlanStep.SOS))
        LOCAL_EXACT[s]?.let { return ParsedIntent.Local(it) }
        ENROLL.matchEntire(s)?.let {
            return ParsedIntent.Local(LocalCommand.ENROLL_PERSON, titleCase(it.groupValues[1]))
        }
        if (s == "enroll") return clarify("Who should I enroll?")
        return matchSimple(s) ?: matchApps(s) ?: matchNavigation(s) ?: matchTap(s) ?: matchAsk(s, text, context)
    }

    private fun matchSimple(s: String): ParsedIntent? = when {
        s in DESCRIBE -> plan(step(PlanStep.DESCRIBE))
        s in READ_SCREEN -> plan(step(PlanStep.READ_SCREEN), ack = "Reading the screen.")
        s in SUMMARIZE -> plan(step(PlanStep.SUMMARIZE), ack = "Summarizing.")
        s in BACK -> plan(step(PlanStep.BACK), ack = "Going back.")
        s in READ_MESSAGES -> readApp("WhatsApp")
        s == "scroll" -> clarify("Up or down?")
        else -> SCROLL[s]?.let { plan(step(PlanStep.SCROLL, it), ack = "Scrolling $it.") }
    }

    private fun readApp(app: String): ParsedIntent = plan(
        step(PlanStep.LAUNCH_APP, app),
        step(PlanStep.WAIT),
        step(PlanStep.READ_SCREEN),
        ack = "Opening $app."
    )

    private fun matchApps(s: String): ParsedIntent? {
        if (s == "open" || s == "launch") return clarify("Which app do you want to open?")
        OPEN_AND_READ.matchEntire(s)?.let { return readApp(appName(it.groupValues[1])) }
        return OPEN.matchEntire(s)?.let {
            val app = appName(it.groupValues[1])
            plan(step(PlanStep.LAUNCH_APP, app), ack = "Opening $app.")
        }
    }

    private fun matchNavigation(s: String): ParsedIntent? {
        val match = NAVIGATE.matchEntire(s) ?: return null
        val destination = match.groups[1]?.value?.removePrefix("the ")?.trim().orEmpty()
        if (destination.isEmpty()) return clarify("Where do you want to go?")
        return plan(step(PlanStep.NAVIGATE, destination), ack = "Getting directions to ${titleCase(destination)}.")
    }

    private fun matchTap(s: String): ParsedIntent? {
        val label = TAP.matchEntire(s)?.groupValues?.get(1) ?: return null
        val risky = RiskyLabels.isRisky(label)
        return plan(step(PlanStep.TAP_LABEL, label), ack = if (risky) null else "Tapping $label.", confirm = risky)
    }

    private fun matchAsk(s: String, original: String, context: ParseContext): ParsedIntent? {
        if (!context.cameraActive) return null
        if (ASK_STARTERS.none { s == it || s.startsWith("$it ") }) return null
        return plan(step(PlanStep.ASK, original.trim()))
    }
}
