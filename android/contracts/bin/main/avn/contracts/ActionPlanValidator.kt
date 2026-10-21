package avn.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class ValidationResult(
    val plan: ActionPlan,
    /** Wire names of steps that were not on the whitelist. */
    val droppedSteps: List<String>,
    /** True when the validator set requires_confirmation itself. */
    val forcedConfirmation: Boolean
)

/**
 * The only way an LLM-produced plan reaches a handler. Works on raw JSON because an unknown enum value
 * would make kotlinx.serialization throw instead of letting us drop the step.
 */
object ActionPlanValidator {
    const val DEFAULT_CLARIFICATION = "I didn't understand that. Please say it another way."

    /** Starting value. */
    const val MAX_STEPS = 8

    /** Wire names, read from the PlanStep serializer so the whitelist has a single source. */
    val allowedSteps: Set<String> = PlanStep.serializer().descriptor.let { d ->
        (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
    }

    /**
     * Starting list. To be unified with RiskyLabelGuard (WS-17) later.
     * `sos` is deliberately not here: its spoken countdown is the cancel window.
     */
    private val riskyWords = setOf(
        "send", "delete", "remove", "buy", "purchase", "pay", "post", "confirm", "order", "transfer",
        "ارسال", "إرسال", "حذف", "مسح", "شراء", "اشتري", "ادفع", "دفع", "تحويل", "نشر", "تأكيد"
    )

    fun isRiskyLabel(label: String?): Boolean =
        label != null && label.lowercase().split(Regex("[^\\p{L}]+")).any { it in riskyWords }

    private fun wireName(item: PlanStepItem): String =
        PlanStep.serializer().descriptor.getElementName(item.step.ordinal)

    /** Parses untrusted JSON (the `data` object of /intent). Never throws. */
    fun parse(raw: String): ValidationResult {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return clarify(emptyList())
        val rawSteps = root["steps"] as? JsonArray ?: JsonArray(emptyList())

        val kept = mutableListOf<PlanStepItem>()
        val dropped = mutableListOf<String>()
        for (element in rawSteps) {
            val obj = element as? JsonObject
            val name = (obj?.get("step") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (obj == null || name == null || name !in allowedSteps) {
                dropped += name ?: "<malformed>"
                continue
            }
            val arg = (obj["arg"] as? JsonPrimitive)?.contentOrNull
            val step = Json.decodeFromJsonElement(PlanStep.serializer(), JsonPrimitive(name))
            kept += PlanStepItem(step, arg)
        }
        if (kept.size > MAX_STEPS) {
            dropped += kept.drop(MAX_STEPS).map { wireName(it) }
        }

        val plan = ActionPlan(
            steps = kept.take(MAX_STEPS),
            requiresConfirmation = (root["requires_confirmation"] as? JsonPrimitive)?.booleanOrNull ?: false,
            spokenAck = (root["spoken_ack"] as? JsonPrimitive)?.contentOrNull,
            clarification = (root["clarification"] as? JsonPrimitive)?.contentOrNull
        )
        return finish(plan, dropped)
    }

    /** For plans already typed (rules, fakes). Same confirmation and clarification rules. */
    fun validate(plan: ActionPlan): ValidationResult = finish(plan, emptyList())

    private fun finish(plan: ActionPlan, dropped: List<String>): ValidationResult {
        if (plan.steps.isEmpty()) {
            val text = plan.clarification?.takeIf { it.isNotBlank() } ?: DEFAULT_CLARIFICATION
            return ValidationResult(plan.copy(clarification = text, requiresConfirmation = false), dropped, false)
        }
        val risky = plan.steps.any { wireName(it) == "tap_label" && isRiskyLabel(it.arg) }
        val forced = risky && !plan.requiresConfirmation
        return ValidationResult(plan.copy(requiresConfirmation = plan.requiresConfirmation || risky), dropped, forced)
    }

    private fun clarify(dropped: List<String>) = ValidationResult(
        ActionPlan(emptyList(), requiresConfirmation = false, clarification = DEFAULT_CLARIFICATION),
        dropped,
        forcedConfirmation = false
    )
}
