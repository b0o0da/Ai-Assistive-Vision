package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.ActionPlan
import avn.contracts.Transcript
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

@Singleton
class FakeEmergencyKeywordDetector @Inject constructor(private val log: JsonLinesLogger) : EmergencyKeywordDetector {
    override val detections: Flow<Long> = emptyFlow()

    override fun start() = log.log("emergency_keyword", "fake_start")

    override fun stop() = log.log("emergency_keyword", "fake_stop")
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
