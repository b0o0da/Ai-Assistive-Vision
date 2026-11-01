package avn.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import avn.app.BuildConfig
import avn.app.core.consent.ConsentScope
import avn.app.core.permissions.Access
import avn.app.core.service.AssistantService
import avn.app.data.SettingEntity
import avn.app.data.SettingKeys
import avn.contracts.Event
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val TABS = listOf("Onboarding", "Settings", "Alert log", "Debug")
private val MOCK_SCENARIOS = listOf("ok", "timeout", "error", "empty")
private val SETTING_FIELDS = listOf(
    SettingKeys.BACKEND_URL to "Backend URL",
    SettingKeys.API_KEY to "Install API key",
    SettingKeys.EMERGENCY_CONTACTS to "Emergency contacts (comma separated, team phones only)",
    SettingKeys.SPEECH_RATE to "Speech rate (1.0 = normal)"
)

@Composable
fun AvnScreens(deps: UiDeps) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row {
            TABS.forEachIndexed { index, name ->
                TextButton(onClick = { tab = index }, enabled = tab != index) { Text(name) }
            }
        }
        when (tab) {
            0 -> OnboardingScreen(deps)
            1 -> SettingsScreen(deps)
            2 -> AlertLogScreen(deps)
            else -> DebugScreen(deps)
        }
    }
}

@Composable
private fun OnboardingScreen(deps: UiDeps) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Access?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val access = pending
        if (!granted && access != null) deps.say(access.deniedSpeech)
        refresh++
    }
    // Special access is granted in system settings, so re-check every time the app comes back.
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        val missing = deps.permissions.missing()
        val summary = if (missing.isEmpty()) {
            "All permissions are granted."
        } else {
            "Setup has ${missing.size} steps left: " + missing.joinToString { it.label }
        }
        deps.say(summary)
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        key(refresh) {
            Access.entries.forEach { access ->
                val granted = deps.permissions.isGranted(access)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${access.label}: ${if (granted) "granted" else "missing"}", Modifier.weight(1f))
                    Button(
                        enabled = !granted,
                        onClick = {
                            deps.say(access.label)
                            val permission = access.permission
                            if (permission != null) {
                                pending = access
                                launcher.launch(permission)
                            } else {
                                val opened = runCatching {
                                    context.startActivity(deps.permissions.settingsIntent(access))
                                }
                                if (opened.isFailure) deps.say("I could not open that settings page.")
                            }
                        }
                    ) { Text("Grant") }
                }
            }
        }

        Text("Consent", style = MaterialTheme.typography.titleMedium)
        ConsentScope.entries.forEach { scope ->
            var on by remember { mutableStateOf(deps.consent.isGranted(scope)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(scope.label, Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        if (it) deps.consent.grant(scope) else deps.consent.revoke(scope)
                        deps.say("${scope.label}: ${if (it) "allowed" else "not allowed"}")
                    }
                )
            }
        }

        Button(onClick = { AssistantService.start(context) }) { Text("Start assistant") }
        Button(onClick = { AssistantService.stop(context) }) { Text("Stop assistant") }
    }
}

@Composable
private fun SettingsScreen(deps: UiDeps) {
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        values = SETTING_FIELDS.associate { (key, _) -> key to (deps.settings.get(key) ?: "") }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SETTING_FIELDS.forEach { (key, label) ->
            OutlinedTextField(
                value = values[key] ?: "",
                onValueChange = {
                    values = values + (key to it)
                    saved = false
                },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = {
                scope.launch {
                    values.forEach { (key, value) -> deps.settings.put(SettingEntity(key, value.trim())) }
                    saved = true
                    deps.say("Settings saved.")
                }
            }
        ) { Text("Save") }
        if (saved) Text("Saved")
    }
}

@Composable
private fun AlertLogScreen(deps: UiDeps) {
    val rows by deps.alertLog.observeRecent(500).collectAsState(initial = emptyList())
    if (rows.isEmpty()) {
        Text("No alerts yet. The engine fills this in Phase 3.")
    } else {
        LazyColumn {
            items(rows, key = { it.id }) { row ->
                Text("${row.level} ${row.type} ${row.label}: ${row.decision} ${row.reason ?: ""}")
            }
        }
    }
}

@Composable
private fun DebugScreen(deps: UiDeps) {
    val scope = rememberCoroutineScope()
    val state by deps.store.state.collectAsState()
    val spoken by deps.speaker.spoken.collectAsState()
    val scenario by deps.settings.observe(SettingKeys.MOCK_SCENARIO).collectAsState(initial = null)
    var transcript by remember { mutableStateOf("") }
    var eventJson by remember { mutableStateOf("") }
    var eventError by remember { mutableStateOf(false) }
    var forcedOffline by remember { mutableStateOf(deps.connectivity.forcedOffline) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (BuildConfig.DEBUG) {
            Button(onClick = { deps.wakeWord.trigger() }) { Text("Fake wake word") }
        }

        OutlinedTextField(
            value = transcript,
            onValueChange = { transcript = it },
            label = { Text("Transcript text") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = transcript.isNotBlank(), onClick = { deps.stt.inject(transcript) }) {
            Text("Inject transcript")
        }

        OutlinedTextField(
            value = eventJson,
            onValueChange = { eventJson = it },
            label = { Text("Event JSON (contract schema)") },
            isError = eventError,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val event = runCatching { Json.decodeFromString(Event.serializer(), eventJson) }.getOrNull()
                eventError = event == null
                if (event != null) deps.engine.submit(event)
            }
        ) { Text("Inject event") }
        if (eventError) Text("Not a valid Event. Unknown fields and enum values are rejected.")

        Button(
            onClick = {
                forcedOffline = !forcedOffline
                deps.connectivity.forceOffline(forcedOffline)
            }
        ) { Text(if (forcedOffline) "Stop forcing offline" else "Force offline") }

        Text("X-Mock-Scenario: ${scenario ?: "ok"}")
        Row {
            MOCK_SCENARIOS.forEach { name ->
                TextButton(
                    onClick = { scope.launch { deps.settings.put(SettingEntity(SettingKeys.MOCK_SCENARIO, name)) } }
                ) { Text(name) }
            }
        }

        Text("App state", style = MaterialTheme.typography.titleMedium)
        Text(state.copy(moduleHealth = emptyMap()).toString())
        state.moduleHealth.forEach { (module, health) -> Text("$module: $health") }

        Text("Fake speaker (last ${spoken.size})", style = MaterialTheme.typography.titleMedium)
        spoken.forEach { Text("${it.level}: ${it.text}") }
    }
}
