package eg.assistive.core.engine

import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.SpeechCandidate
import eg.assistive.core.model.SpeechRequest

fun interface Clock {
    fun nowMs(): Long
}

/** Utterance callbacks. The Android adapter maps UtteranceProgressListener onto these. */
interface UtteranceListener {
    fun onStarted(utteranceId: String)

    /** Finished normally. */
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)

    /** Cut off by [SpeechOutput.stop]. Must NOT release the next item. */
    fun onStopped(utteranceId: String)
}

/**
 * The single speech output of the app. Only the engine calls it.
 * To interrupt, the engine calls [stop] and then [speak]; the adapter queues speak() calls (QUEUE_ADD).
 */
interface SpeechOutput {
    fun setListener(listener: UtteranceListener)
    fun speak(request: SpeechRequest)
    fun stop()
}

/**
 * What the app is doing right now (WS-16 interface: updateContext(listening, speaking, moving,
 * navigationActive, offline, sosActive)). Defaults are the conservative ones for a walking user.
 */
data class EngineContext(
    /** The microphone is listening to the user: only critical alerts may talk. */
    val listening: Boolean = false,
    /** Reported by the platform. Informational: the engine tracks its own speech through callbacks. */
    val speaking: Boolean = false,
    /** User is walking: unrequested INFO stays silent. */
    val moving: Boolean = true,
    /** Navigation is running: unrequested INFO stays silent so route instructions are not buried. */
    val navigationActive: Boolean = false,
    /** No internet. Informational for the engine; pipelines use it to choose local fallbacks. */
    val offline: Boolean = false,
    /** An SOS is in progress: unrequested INFO stays silent. */
    val sosActive: Boolean = false,
)

/** The only way into the engine (WS-16). */
interface EngineInput {
    fun submit(event: Event): DecisionRecord
    fun submit(candidate: SpeechCandidate): DecisionRecord
    fun updateContext(context: EngineContext)
}

data class EngineConfig(
    val minConfidence: Double = 0.40,
    /** risk score >= this is CRITICAL. */
    val criticalThreshold: Double = 0.80,
    /** risk score >= this (and below critical) is WARNING, otherwise INFO. */
    val warningThreshold: Double = 0.40,
    val cooldownCriticalMs: Long = 2_000,
    val cooldownWarningMs: Long = 5_000,
    val cooldownInfoMs: Long = 10_000,
    val duplicateWindowMs: Long = 3_000,
    val ttlCriticalMs: Long = 2_000,
    val ttlWarningMs: Long = 4_000,
    val ttlInfoMs: Long = 8_000,
    /** Max waiting items that were NOT asked for by the user. */
    val maxQueuedAutomatic: Int = 8,
    /** Safety net if the utterance callback never arrives. */
    val maxSpeechMs: Long = 20_000,
    val logCapacity: Int = 500,
) {
    fun cooldownFor(p: Priority): Long = when (p) {
        Priority.CRITICAL -> cooldownCriticalMs
        Priority.WARNING -> cooldownWarningMs
        Priority.INFO -> cooldownInfoMs
    }

    fun ttlFor(p: Priority): Long = when (p) {
        Priority.CRITICAL -> ttlCriticalMs
        Priority.WARNING -> ttlWarningMs
        Priority.INFO -> ttlInfoMs
    }
}
