package avn.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** contracts/schemas/event.schema.json. `side` and `distanceBand` are required but may be null. */
@Serializable
data class Event(
    val id: String,
    val source: Source,
    val type: EventType,
    val label: String,
    val side: Side?,
    @SerialName("distance_band") val distanceBand: DistanceBand?,
    val confidence: Double,
    /** Producers send 0; the engine computes the real value. */
    val risk: Int,
    val timestamp: Long
)

@Serializable
data class SpeechRequest(
    @SerialName("utterance_id") val utteranceId: String,
    val text: String,
    val level: Level,
    val source: Source,
    val interruptible: Boolean,
    @SerialName("event_id") val eventId: String? = null
)

/**
 * What a non-camera producer hands to the engine instead of speaking.
 * PROPOSED shape: not yet in contracts/schemas/android.schema.json, to be added before the Gate 1 freeze.
 */
@Serializable
data class SpeechCandidate(
    val text: String,
    val source: Source,
    val type: EventType,
    @SerialName("user_requested") val userRequested: Boolean = false,
    @SerialName("time_sensitive") val timeSensitive: Boolean = false,
    @SerialName("event_id") val eventId: String? = null
)

@Serializable
data class PlanStepItem(val step: PlanStep, val arg: String? = null)

/**
 * Contract between intent routing and handlers (POST /intent `data`, IntentRouter.route output).
 *
 * - [steps]: ordered, whitelisted steps. Unknown steps never reach a handler (see ActionPlanValidator).
 * - [requiresConfirmation]: true if any step could send, delete, buy or pay. Handlers must ask first.
 * - [spokenAck]: short sentence spoken immediately, or null.
 * - [clarification]: question to speak when there is no valid step, or null.
 */
@Serializable
data class ActionPlan(
    val steps: List<PlanStepItem>,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean,
    @SerialName("spoken_ack") val spokenAck: String? = null,
    val clarification: String? = null
)

@Serializable
data class NodeInfo(
    val text: String,
    @SerialName("content_description") val contentDescription: String? = null,
    @SerialName("view_id") val viewId: String? = null,
    @SerialName("class_name") val className: String? = null,
    val clickable: Boolean,
    val scrollable: Boolean,
    @SerialName("is_password") val isPassword: Boolean,
    val bounds: List<Int>? = null
)

@Serializable
data class ScreenFlags(
    val secure: Boolean = false,
    val empty: Boolean = false,
    val unlabeled: Boolean = false,
    @SerialName("deny_listed") val denyListed: Boolean = false
)

@Serializable
data class ScreenSnapshot(
    @SerialName("package") val packageName: String,
    @SerialName("screen_type") val screenType: ScreenType,
    val nodes: List<NodeInfo>,
    val text: String,
    @SerialName("clickable_labels") val clickableLabels: List<String>,
    val flags: ScreenFlags
)

// Phone-internal seam types with no JSON schema yet (architecture WS-07, WS-12, WS-20). Phase 2 finalises them.

@Serializable
data class Transcript(val text: String, val confidence: Double, val language: String)

@Serializable
data class OcrResult(val text: String, val confidence: Double, val language: String, val source: String)

@Serializable
data class NavState(
    val active: Boolean = false,
    @SerialName("step_index") val stepIndex: Int = 0,
    @SerialName("off_route") val offRoute: Boolean = false
)

const val ENVELOPE_VERSION = "1.0"

/** contracts/schemas/envelope.schema.json error object. */
@Serializable
data class ErrorInfo(val code: ErrorCode, val retryable: Boolean)

/**
 * contracts/schemas/envelope.schema.json. Every backend response uses this shape.
 * `data` may carry personal content (scene text, screen summaries, face names); it is never persisted
 * or logged by default. `message` and `error` never carry personal content.
 */
@Serializable
data class ApiEnvelope(
    val version: String,
    val ok: Boolean,
    val fallback: Boolean,
    val message: String,
    val data: JsonElement? = null,
    @SerialName("request_id") val requestId: String,
    val error: ErrorInfo? = null
) {
    val isSupportedVersion: Boolean get() = version == ENVELOPE_VERSION

    /** Sentence to speak when there is no usable data, else null. */
    val speakable: String? get() = if (!ok || fallback) message.ifBlank { null } else null
}
