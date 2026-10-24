package avn.engine

import avn.contracts.Level
import avn.contracts.SpeechRequest

/**
 * Bounded, ordered speech queue (architecture.md WS-16 deep dive, AVN-56): "One queue, one
 * speaker. Order: level, then risk, then age. Maximum depth 3; overflow drops the lowest-priority
 * item and logs it." This class is only the queue itself -- thresholds, cooldowns, duplicate
 * suppression and turning an `Event` into a risk score are WS16-03's job, not this ticket's; this
 * ticket's done-when is the queue's own three invariants (below).
 *
 * **Order.** [Level]'s declared enum order is already highest-priority first (Critical, Warning,
 * Info), so sorting by `level.ordinal` ascending puts Critical first. Within a level, higher risk
 * sorts first. Within equal level and risk, the item that has been waiting longer sorts first, so
 * a long-waiting item is never starved by a stream of same-priority newcomers.
 *
 * **Overflow (done when: "drops lowest priority first").** "Lowest priority" is simply the far
 * end of that same order -- except Critical is never the item dropped, even if it is technically
 * the oldest/lowest-risk item in an all-Critical queue (done when: "Critical never dropped"): when
 * the item that would otherwise be dropped is Critical, the newly-arriving item is dropped instead
 * and the Criticals already queued are left alone.
 *
 * **Expiry (done when: "expired items never spoken", "never speak stale hazards").** Stale items
 * (older than their level's expiry, [SpeechQueueConfig.expiryMsFor]) are purged every time the
 * queue is read or written, so expiry does not depend on a background timer, only on the next
 * clock read -- an expired item can never be the one [dequeueNext] or [peekAll] returns.
 *
 * Every request dropped for either reason is reported once through [onDecision] so a caller can
 * fold it into the engine's own decision log (architecture.md WS-16 reason codes `queue_overflow`,
 * `expired`); this class does no logging itself, since logging in this repo is JSON-lines and
 * `:engine` has nothing to do with how that gets written.
 *
 * Pure Kotlin, no Android dependencies, no I/O beyond the config load; never touches the camera,
 * microphone or network -- it only holds [SpeechRequest]s someone else built and reports back what
 * happened to the ones that did not end up speakable. Stateful and not thread-safe, like the
 * engine's other per-session components.
 */
class SpeechQueue(
    private val clock: Clock = SystemClock(),
    private val config: SpeechQueueConfig = SpeechQueueConfig.load(),
    private val onDecision: (QueueDecision) -> Unit = {}
) {
    private data class Entry(val request: SpeechRequest, val risk: Int, val enqueuedAtMs: Long)

    private val entries = mutableListOf<Entry>()

    /** Ascending = highest priority first: lower level ordinal, then higher risk, then older age. */
    private val priorityOrder: Comparator<Entry> =
        compareBy<Entry> { it.request.level.ordinal }
            .thenByDescending { it.risk }
            .thenBy { it.enqueuedAtMs }

    /** Number of live (not yet purged) entries; may briefly include stale ones until the next
     * read or write triggers a purge. */
    val size: Int get() = entries.size

    /** Adds [request] to the queue. If the queue is already at [SpeechQueueConfig.maxDepth] after
     * adding it, the lowest-priority entry is dropped -- the new one, if that lowest-priority
     * entry would otherwise be Critical. */
    fun enqueue(request: SpeechRequest, risk: Int = 0) {
        purgeExpired()
        val entry = Entry(request, risk, clock.nowMs())
        entries += entry
        if (entries.size <= config.maxDepth) return

        val lowestPriority = entries.maxWith(priorityOrder)
        val toDrop = if (lowestPriority.request.level == Level.CRITICAL) entry else lowestPriority
        entries -= toDrop
        onDecision(QueueDecision(toDrop.request, "queue_overflow"))
    }

    /** Removes and returns the highest-priority live request, or null if the queue is empty once
     * stale entries are purged. */
    fun dequeueNext(): SpeechRequest? {
        purgeExpired()
        val next = entries.minWithOrNull(priorityOrder) ?: return null
        entries -= next
        return next.request
    }

    /** Every still-live request, highest priority first, without removing them. */
    fun peekAll(): List<SpeechRequest> {
        purgeExpired()
        return entries.sortedWith(priorityOrder).map { it.request }
    }

    private fun purgeExpired() {
        if (entries.isEmpty()) return
        val now = clock.nowMs()
        val stale = entries.filter { entry ->
            val expiryMs = config.expiryMsFor(entry.request.level) ?: return@filter false
            now - entry.enqueuedAtMs >= expiryMs
        }
        if (stale.isEmpty()) return
        entries -= stale
        stale.forEach { onDecision(QueueDecision(it.request, "expired")) }
    }
}

/** One decision [SpeechQueue] made about a request that did not end up being spoken, with the
 * architecture.md WS-16 reason code (`queue_overflow` or `expired`). */
data class QueueDecision(val request: SpeechRequest, val reason: String)
