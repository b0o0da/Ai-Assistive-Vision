package avn.app.voice

import avn.contracts.ActionPlan
import avn.contracts.PlanStep
import avn.contracts.Transcript
import kotlin.coroutines.cancellation.CancellationException

/** The cloud `/intent` path. Returns null on any failure so the router can speak the contract fallback. */
interface CloudIntent {
    suspend fun plan(transcript: Transcript): ActionPlan?
}

object PlanValidator {
    const val NOT_UNDERSTOOD = "I didn't understand that. Please say it another way."

    fun validate(plan: ActionPlan): ActionPlan {
        val risky = plan.steps.any { it.step == PlanStep.TAP_LABEL && it.arg?.let(RiskyLabels::isRisky) == true }
        val needsClarification = plan.steps.isEmpty() && plan.clarification == null
        return plan.copy(
            requiresConfirmation = plan.requiresConfirmation || risky,
            clarification = if (needsClarification) NOT_UNDERSTOOD else plan.clarification
        )
    }
}

/**
 * Rules first, always, so emergencies and common commands work offline and in milliseconds.
 * An empty plan with no clarification means the command was handled locally.
 */
class RuleIntentRouter(
    private val localCommands: LocalCommandHandler,
    private val parser: RuleIntentParser = RuleIntentParser(),
    private val cloud: CloudIntent? = null,
    private val isOnline: () -> Boolean = { true },
    private val context: () -> ParseContext = { ParseContext() }
) : IntentRouter {
    override suspend fun route(transcript: Transcript): ActionPlan {
        when (val parsed = parser.parse(transcript.text, context())) {
            is ParsedIntent.Plan -> return PlanValidator.validate(parsed.plan)
            is ParsedIntent.Local -> {
                localCommands.handle(parsed.command, parsed.arg)
                return ActionPlan(emptyList(), false)
            }
            null -> Unit
        }
        if (!isOnline()) return clarification(OFFLINE)
        val cloudPlan = fetchCloudPlan(transcript)
        return if (cloudPlan != null) PlanValidator.validate(cloudPlan) else clarification(PlanValidator.NOT_UNDERSTOOD)
    }

    private suspend fun fetchCloudPlan(transcript: Transcript): ActionPlan? = try {
        cloud?.plan(transcript)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun clarification(message: String) = ActionPlan(emptyList(), false, null, message)

    private companion object {
        const val OFFLINE = "I can't do that offline."
    }
}
