package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.ActionPlan
import avn.contracts.SpeechRequest
import avn.contracts.Transcript
import avn.engine.SpeechOutput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class WakeEvent(val timestampMs: Long, val confidence: Float)

interface WakeWord {
    val wakes: Flow<WakeEvent>

    fun start()

    fun stop()
}

/** Feeds the SOS fast path directly: no STT, no LLM, no network. */
interface EmergencyKeywordDetector {
    /** Emits the detection timestamp in epoch ms. */
    val detections: Flow<Long>

    fun start()

    fun stop()
}

interface SttEngine {
    /** Null on silence or timeout. */
    suspend fun listen(timeoutMs: Long): Transcript?
}

interface IntentRouter {
    suspend fun route(transcript: Transcript): ActionPlan
}

interface ActionRouter {
    suspend fun execute(plan: ActionPlan)
}

/** Logs instead of speaking, and keeps the last utterances in memory for the Debug screen and tests. */
@Singleton
class FakeSpeaker @Inject constructor(private val log: JsonLinesLogger) : SpeechOutput {
    private val mutable = MutableStateFlow<List<SpeechRequest>>(emptyList())
    val spoken: StateFlow<List<SpeechRequest>> = mutable.asStateFlow()

    override val isSpeaking: Boolean = false

    override fun speak(request: SpeechRequest) {
        val fields = mapOf("utterance" to request.utteranceId, "level" to request.level, "chars" to request.text.length)
        log.log("speaker", "fake_speak", fields = fields)
        mutable.update { (it + request).takeLast(20) }
    }

    override fun stop(utteranceId: String?) = log.log("speaker", "fake_stop")
}

@Singleton
class FakeEmergencyKeywordDetector @Inject constructor(private val log: JsonLinesLogger) : EmergencyKeywordDetector {
    override val detections: Flow<Long> = emptyFlow()

    override fun start() = log.log("emergency_keyword", "fake_start")

    override fun stop() = log.log("emergency_keyword", "fake_stop")
}

@Singleton
class FakeSttEngine @Inject constructor(private val log: JsonLinesLogger) : SttEngine {
    private val injected = Channel<String>(Channel.CONFLATED)

    /** Debug text box. */
    fun inject(text: String) {
        log.log("stt", "fake_inject", fields = mapOf("chars" to text.length))
        injected.trySend(text)
    }

    override suspend fun listen(timeoutMs: Long): Transcript? =
        withTimeoutOrNull(timeoutMs) { Transcript(injected.receive(), confidence = 1.0, language = "en") }
}

@Singleton
class FakeIntentRouter @Inject constructor(private val log: JsonLinesLogger) : IntentRouter {
    override suspend fun route(transcript: Transcript): ActionPlan {
        log.log("intent", "fake_route")
        return ActionPlan(
            steps = emptyList(),
            requiresConfirmation = false,
            clarification = "Intent routing is not built yet."
        )
    }
}

@Singleton
class FakeActionRouter @Inject constructor(private val log: JsonLinesLogger) : ActionRouter {
    override suspend fun execute(plan: ActionPlan) = log.log(
        "router",
        "fake_execute",
        fields = mapOf(
            "steps" to plan.steps.size
        )
    )
}
