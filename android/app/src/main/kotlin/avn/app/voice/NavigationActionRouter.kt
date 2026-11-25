package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.Navigation
import avn.contracts.ActionPlan
import avn.contracts.PlanStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WS-20's side of the `ActionRouter` contract (architecture.md WS-14: "ActionPlan to the action
 * router, which calls ... navigation ... modules"): handles `PlanStep.NAVIGATE` by calling
 * [Navigation.start], nothing else. Confirmation flow, speaking `spokenAck`/`clarification`, and
 * every other step are WS-14's real router to build; unhandled steps are only logged here (same
 * as [FakeActionRouter]) so enabling this seam doesn't silently no-op the rest of voice control.
 */
@Singleton
class NavigationActionRouter @Inject constructor(private val navigation: Navigation, private val log: JsonLinesLogger) :
    ActionRouter {
    override suspend fun execute(plan: ActionPlan) {
        for (item in plan.steps) {
            val destination = item.arg
            if (item.step == PlanStep.NAVIGATE && destination != null) {
                val started = navigation.start(destination)
                log.log("router", "navigate", fields = mapOf("started" to started))
            } else {
                log.log("router", "unhandled_step", fields = mapOf("step" to item.step.name))
            }
        }
    }
}
