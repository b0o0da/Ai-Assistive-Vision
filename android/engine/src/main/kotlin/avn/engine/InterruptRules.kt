package avn.engine

import avn.contracts.Level
import avn.contracts.Source
import avn.contracts.SpeechRequest

/**
 * Implements the "Speech queue and interruptions" table from `docs/architecture.md` section 5
 * (AVN-55 / WS-16): Critical cuts in, Warning waits for the next gap, Info is spoken only when
 * idle and stationary or when asked.
 *
 * This is deliberately narrower than the full priority engine: it owns only the queue and the
 * interruption/requeue/hold decisions once a [SpeechItem] reaches it. Risk scoring, Gate
 * 1/2/3 thresholds and cooldown/duplicate suppression (the other tables in section 5) are a
 * separate, larger piece of work and are out of scope for this component -- see
 * `Deliver: engine/interrupt_rules.*` on the ticket.
 *
 * Scope notes (see docs comments below for detail): some table rows need integrations that do
 * not exist yet and are intentionally not modelled here:
 * - "re-queued from its current sentence": [SpeechOutput] has no utterance-progress callback yet
 *   (its own doc comment says those land "with the engine in Phase 3"), so a requeued utterance
 *   restarts from the beginning rather than from the interrupted sentence.
 * - "Wake word fires while speaking", "Critical arrives while listening", "Navigation instruction
 *   due" and "Offline or degraded module" depend on STT/wake-word, navigation and health-signal
 *   modules that are not wired up yet; this component exposes the context flags
 *   ([updateContext]) those rows need, but does not itself integrate with those modules.
 * - "Two Criticals: highest risk first": [SpeechRequest] does not carry a risk score (that lives
 *   on `Event`, a layer up, and risk scoring is a separate table/ticket), so two queued Criticals
 *   are ordered by arrival instead.
 */
class InterruptRules(
    private val speaker: SpeechOutput,
    private val clock: Clock,
    private val maxQueueDepth: Int = 3,
    /** How long the context must be continuously idle and stationary before Info is allowed to
     * speak, so a momentary blip (e.g. one step taken while otherwise standing still) does not
     * flap Info in and out of eligibility. This is the reason this component needs a [Clock]. */
    private val idleDebounceMillis: Long = 1_500
) {
    private var context = EngineContext()
    private var idleSinceMillis: Long? = null

    private var currentlySpeaking: SpeechItem? = null
    private val queue = mutableListOf<Enqueued>()
    private val held = mutableListOf<Enqueued>()
    private var sequence = 0L

    /** What is being spoken right now, or null if the speaker is free. */
    val speakingNow: SpeechRequest?
        get() = currentlySpeaking?.request

    /** Snapshot of the queue, highest priority first. For tests/debugging only. */
    val queuedRequests: List<SpeechRequest>
        get() = queue.map { it.item.request }

    /** Snapshot of items held back (by SOS or by the idle/stationary rule). For tests/debugging only. */
    val heldRequests: List<SpeechRequest>
        get() = held.map { it.item.request }

    /**
     * Voice and navigation report their state here so neither needs to know the other's rules,
     * or this component's rules (architecture.md section 5, "Voice interaction").
     */
    fun updateContext(
        listening: Boolean = context.listening,
        speaking: Boolean = context.speaking,
        moving: Boolean = context.moving,
        navigationActive: Boolean = context.navigationActive,
        offline: Boolean = context.offline,
        sosActive: Boolean = context.sosActive
    ) {
        context = EngineContext(listening, speaking, moving, navigationActive, offline, sosActive)
        idleSinceMillis = if (isInstantIdle(context)) (idleSinceMillis ?: clock.nowMillis()) else null
        promoteEligibleHeldItems()
        dispatchNext()
    }

    /** Submits one piece of speech for the interrupt rules to decide on. */
    fun submit(item: SpeechItem): Decision {
        val seq = sequence++
        if (context.sosActive && !item.sosCountdown) {
            hold(item, seq)
            return Decision.HELD
        }
        return when (item.request.level) {
            Level.CRITICAL -> submitCritical(item, seq)
            Level.WARNING, Level.INFO -> submitQueueable(item, seq)
        }
    }

    /**
     * Must be called when [speaker] finishes, or is stopped on, an utterance -- this is the
     * "gap" the table talks about. [SpeechOutput] has no such callback yet (Phase 3); tests and
     * any caller in the meantime call this directly once they know speech has stopped.
     */
    fun notifyFinished(utteranceId: String) {
        if (currentlySpeaking?.request?.utteranceId == utteranceId) {
            currentlySpeaking = null
            dispatchNext()
        }
    }

    private fun submitCritical(item: SpeechItem, seq: Long): Decision {
        val speaking = currentlySpeaking
        if (speaking == null) {
            dispatch(item)
            return Decision.SPOKEN
        }
        if (speaking.request.level == Level.CRITICAL) {
            // Two Criticals: don't cut the first one off; the second follows with no gap once it ends.
            enqueue(item, seq)
            return Decision.QUEUED
        }
        // "Critical arrives while anything is speaking": flush the speaker immediately.
        speaker.stop(speaking.request.utteranceId)
        currentlySpeaking = null
        requeueOrDrop(speaking)
        dispatch(item)
        return Decision.SPOKEN
    }

    private fun submitQueueable(item: SpeechItem, seq: Long): Decision {
        if (item.request.level == Level.INFO && !canSpeakInfoNow(item)) {
            hold(item, seq)
            return Decision.HELD
        }
        if (currentlySpeaking == null && queue.isEmpty()) {
            dispatch(item)
            return Decision.SPOKEN
        }
        return enqueue(item, seq)
    }

    /** The item that was flushed off the speaker: requeue it if it was user-requested or
     * navigation, drop it otherwise (Info and scene detail/answers). */
    private fun requeueOrDrop(flushed: SpeechItem) {
        val requeue = flushed.userRequested || flushed.request.source == Source.NAVIGATION
        if (requeue) enqueue(flushed, sequence++)
    }

    private fun hold(item: SpeechItem, seq: Long) {
        held += Enqueued(item, seq)
        trim(held)
    }

    private fun enqueue(item: SpeechItem, seq: Long): Decision {
        queue += Enqueued(item, seq)
        queue.sortWith(compareBy({ priorityRank(it.item) }, { it.seq }))
        if (queue.size > maxQueueDepth) {
            val dropped = queue.removeAt(queue.size - 1)
            if (dropped.seq == seq) return Decision.DROPPED
        }
        return Decision.QUEUED
    }

    private fun dispatchNext() {
        if (currentlySpeaking != null) return
        while (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            if (next.item.request.level == Level.INFO && !canSpeakInfoNow(next.item)) {
                hold(next.item, next.seq)
                continue
            }
            dispatch(next.item)
            return
        }
    }

    private fun dispatch(item: SpeechItem) {
        currentlySpeaking = item
        speaker.speak(item.request)
    }

    /** Re-tries held items (SOS release or the idle/stationary debounce clearing). */
    private fun promoteEligibleHeldItems() {
        if (held.isEmpty()) return
        val stillHeld = mutableListOf<Enqueued>()
        for (entry in held.sortedBy { it.seq }) {
            val stillSosBlocked = context.sosActive && !entry.item.sosCountdown
            val eligible = !stillSosBlocked &&
                (entry.item.request.level != Level.INFO || canSpeakInfoNow(entry.item))
            if (eligible) enqueue(entry.item, entry.seq) else stillHeld += entry
        }
        held.clear()
        held += stillHeld
    }

    private fun trim(list: MutableList<Enqueued>) {
        if (list.size <= maxQueueDepth) return
        list.sortWith(compareBy({ priorityRank(it.item) }, { it.seq }))
        while (list.size > maxQueueDepth) list.removeAt(list.size - 1)
    }

    private fun canSpeakInfoNow(item: SpeechItem): Boolean {
        if (item.userRequested) return true
        val since = idleSinceMillis ?: return false
        return clock.nowMillis() - since >= idleDebounceMillis
    }

    private fun isInstantIdle(context: EngineContext): Boolean =
        !context.moving && !context.listening && !context.navigationActive

    private fun priorityRank(item: SpeechItem): Int = when (item.request.level) {
        Level.CRITICAL -> 0
        Level.WARNING -> 1
        Level.INFO -> if (item.userRequested) 2 else 3
    }

    private data class Enqueued(val item: SpeechItem, val seq: Long)
}

/** What [InterruptRules.submit] did with an item. */
enum class Decision {
    /** Dispatched to the speaker right away. */
    SPOKEN,

    /** Waiting in the queue for a gap. */
    QUEUED,

    /** Waiting because SOS is active (and this isn't the countdown) or Info isn't allowed to
     * speak yet; may still be promoted into the queue later, see [InterruptRules.updateContext]. */
    HELD,

    /** Overflowed the max queue depth and was discarded outright. */
    DROPPED
}

/**
 * Context signals from voice and navigation. Fed in via [InterruptRules.updateContext] so those
 * modules never need to know the interrupt rules themselves (architecture.md section 5,
 * "Voice interaction").
 */
data class EngineContext(
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val moving: Boolean = false,
    val navigationActive: Boolean = false,
    val offline: Boolean = false,
    val sosActive: Boolean = false
)

/**
 * A [SpeechRequest] plus the one extra bit the interruption table needs that the frozen
 * [SpeechRequest] contract does not carry: whether this is a direct answer to a user voice
 * command ("asked"), and whether it is SOS countdown/cancel speech (the only thing allowed
 * through while SOS is active). Everything else the table needs -- level and "was this
 * navigation" -- is already on [SpeechRequest] itself via `level` and `source == Source.NAVIGATION`.
 *
 * This type is engine-internal only: it is never serialized and never crosses the
 * EngineInput/SpeechOutput contract boundary, so it does not touch the frozen contract layer in
 * `:contracts`.
 */
data class SpeechItem(val request: SpeechRequest, val userRequested: Boolean = false, val sosCountdown: Boolean = false)
