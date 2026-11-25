package avn.app.voice

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.Navigation
import avn.contracts.ActionPlan
import avn.contracts.NavState
import avn.contracts.PlanStep
import avn.contracts.PlanStepItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WS-20's side of the ActionRouter contract: only `PlanStep.NAVIGATE` is handled here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationActionRouterTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class RecordingNavigation : Navigation {
        var lastDestination: String? = null
        override val state: StateFlow<NavState> = MutableStateFlow(NavState())
        override suspend fun start(destination: String): Boolean {
            lastDestination = destination
            return true
        }
        override fun cancel() = Unit
    }

    @Test
    fun aNavigateStepStartsNavigationWithItsDestination() = runBlocking {
        val navigation = RecordingNavigation()
        val router = NavigationActionRouter(navigation, JsonLinesLogger(context))
        val plan =
            ActionPlan(
                steps = listOf(PlanStepItem(PlanStep.NAVIGATE, "nearest pharmacy")),
                requiresConfirmation = false
            )

        router.execute(plan)

        assertEquals("nearest pharmacy", navigation.lastDestination)
    }

    @Test
    fun aNavigateStepWithNoDestinationDoesNotStartNavigation() = runBlocking {
        val navigation = RecordingNavigation()
        val router = NavigationActionRouter(navigation, JsonLinesLogger(context))
        val plan = ActionPlan(steps = listOf(PlanStepItem(PlanStep.NAVIGATE, null)), requiresConfirmation = false)

        router.execute(plan)

        assertNull(navigation.lastDestination)
    }

    @Test
    fun aNonNavigateStepDoesNotStartNavigation() = runBlocking {
        val navigation = RecordingNavigation()
        val router = NavigationActionRouter(navigation, JsonLinesLogger(context))
        val plan = ActionPlan(steps = listOf(PlanStepItem(PlanStep.DESCRIBE, null)), requiresConfirmation = false)

        router.execute(plan)

        assertNull(navigation.lastDestination)
    }
}
