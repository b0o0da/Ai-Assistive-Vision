package avn.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import avn.app.BuildConfig
import avn.app.voice.FakeLocalCommandHandler
import avn.app.voice.LocalCommand
import avn.app.voice.RuleIntentRouter
import avn.contracts.ActionPlan
import avn.contracts.Transcript
import kotlinx.coroutines.launch

/** Debug builds only: type a command and see how the rule router reads it, with no STT, engine or network. */
@Composable
fun TypedRouteDebug() {
    if (BuildConfig.DEBUG) TypedRouteDebugContent()
}

@Composable
private fun TypedRouteDebugContent() {
    val scope = rememberCoroutineScope()
    val local = remember { FakeLocalCommandHandler() }
    val router = remember { RuleIntentRouter(localCommands = local) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Column {
        Text("Typed command", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Command text") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = typed.isNotBlank(),
            onClick = {
                scope.launch {
                    val handledBefore = local.commands.size
                    val plan = router.route(Transcript(typed, confidence = 1.0, language = "en"))
                    result = describe(plan, local.commands.drop(handledBefore))
                }
            }
        ) { Text("Route") }
        if (result.isNotEmpty()) Text(result)
    }
}

private fun describe(plan: ActionPlan, handled: List<Pair<LocalCommand, String?>>): String {
    if (handled.isNotEmpty()) {
        val names = handled.map { (command, arg) -> listOfNotNull(command.name, arg).joinToString(" ") }
        return "Local command: ${names.joinToString()}"
    }
    val steps = plan.steps.joinToString(" > ") { listOfNotNull(it.step.name, it.arg).joinToString(" ") }
    val lines = listOfNotNull(
        steps.ifEmpty { null }?.let { "Steps: $it" },
        plan.spokenAck?.let { "Says: $it" },
        plan.clarification?.let { "Asks: $it" },
        "Confirmation required".takeIf { plan.requiresConfirmation }
    )
    return lines.joinToString("\n").ifEmpty { "Nothing to do" }
}
