package eg.assistive.core.engine

import eg.assistive.core.model.DistanceBand
import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.SpeechCandidate
import eg.assistive.core.model.SpeechRequest

/**
 * WS-16 Priority and context engine.
 *
 * Every pipeline submits [Event]s or [SpeechCandidate]s through [EngineInput]. The engine is the ONLY
 * caller of [SpeechOutput]. For each input it decides: speak now, interrupt, wait in the queue, or
 * suppress, and it logs EVERY decision (suppressed ones too) in [log].
 *
 *  - event -> risk score -> threshold -> level (see [RiskModel])
 *  - CRITICAL interrupts anything interruptible. WARNING and INFO wait for a gap.
 *  - While the user is being listened to, only CRITICAL may speak; the rest waits.
 *  - Unrequested INFO is silent while moving, navigating or during SOS.
 *  - Per-key cooldown and duplicate-text suppression. Getting closer or more severe bypasses both.
 *
 * Pure Kotlin, no Android. Thread-safe: the camera thread and the TTS callback thread may both call it.
 */
class PriorityEngine(
    private val output: SpeechOutput,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
) : EngineInput, UtteranceListener {
    val log = DecisionLog(config.logCapacity)
    val alertLog = AlertLogView(log)

    @Volatile
    var context: EngineContext = EngineContext()
        private set

    private class Candidate(
        val id: String,
        val source: String,
        val kind: String,
        val type: String,
        val text: String,
        val display: String,
        val level: Priority,
        val score: Double,
        val userRequested: Boolean,
        val key: String,
        val ttlMs: Long,
        val band: DistanceBand,
        val interruptible: Boolean,
        val eventId: String?,
    )

    private class Pending(val c: Candidate, val enqueuedMs: Long, val seq: Long)
    private class Spoken(val timeMs: Long, val level: Priority, val band: DistanceBand)
    private class Speaking(val c: Candidate, val utteranceId: String, val sinceMs: Long)

    private val queue = ArrayList<Pending>()
    private val lastSpoken = HashMap<String, Spoken>()
    private val recentTexts = HashMap<String, Long>()
    private var speaking: Speaking? = null
    private var queueSeq = 0L
    private var frontSeq = -1L
    private var utteranceCounter = 0L

    init {
        output.setListener(this)
    }

    // ---------------------------------------------------------------- EngineInput

    @Synchronized
    override fun submit(event: Event): DecisionRecord {
        val now = clock.nowMs()
        val invalid = RiskModel.validate(event)
        if (invalid != null) {
            return write(
                now, event.id, event.source, "event", event.type, event.label.ifBlank { event.type },
                null, null, Outcome.SUPPRESSED, Reason.INVALID, invalid,
            )
        }
        val a = RiskModel.assess(event, config)
        val text = RiskModel.phrase(event)
        val c = Candidate(
            id = event.id, source = event.source, kind = "event", type = event.type,
            text = text, display = text, level = a.level, score = a.score, userRequested = false,
            key = event.type + ":" + (event.trackId?.toString() ?: (event.side.name + ":" + event.label.lowercase())),
            ttlMs = config.ttlFor(a.level), band = event.distanceBand,
            interruptible = a.level != Priority.CRITICAL, eventId = event.id,
        )
        val conf = event.confidence
        if (event.type != RiskModel.SOS && conf != null && conf < config.minConfidence) {
            return log(now, c, Outcome.SUPPRESSED, Reason.LOW_CONFIDENCE, "confidence $conf < ${config.minConfidence}")
        }
        return process(c, now)
    }

    @Synchronized
    override fun submit(candidate: SpeechCandidate): DecisionRecord {
        val now = clock.nowMs()
        val display = if (candidate.sensitive) "[redacted ${candidate.text.length} chars]" else candidate.text
        if (candidate.text.isBlank()) {
            return write(
                now, candidate.id, candidate.source, "speech", "speech", display,
                candidate.level, null, Outcome.SUPPRESSED, Reason.INVALID, "empty text",
            )
        }
        val c = Candidate(
            id = candidate.id, source = candidate.source, kind = "speech", type = "speech",
            text = candidate.text, display = display, level = candidate.level,
            score = RiskModel.levelScore(candidate.level), userRequested = candidate.userRequested,
            key = candidate.dedupeKey ?: (candidate.source + ":" + normalize(candidate.text)),
            ttlMs = candidate.ttlMs ?: config.ttlFor(candidate.level), band = DistanceBand.UNKNOWN,
            interruptible = candidate.interruptible ?: (candidate.level != Priority.CRITICAL),
            eventId = candidate.eventId,
        )
        return process(c, now)
    }

    @Synchronized
    override fun updateContext(context: EngineContext) {
        this.context = context
        housekeeping(clock.nowMs()) // e.g. listening just ended: release what was held
    }

    // ---------------------------------------------------------------- UtteranceListener

    override fun onStarted(utteranceId: String) {}

    override fun onDone(utteranceId: String) = finish(utteranceId)

    override fun onError(utteranceId: String) = finish(utteranceId)

    /** Stopped by us (interruption): the next item is already being handled, so do nothing. */
    override fun onStopped(utteranceId: String) {}

    @Synchronized
    private fun finish(utteranceId: String) {
        val s = speaking
        if (s == null || s.utteranceId != utteranceId) return // stale callback of an older utterance
        speaking = null
        housekeeping(clock.nowMs())
    }

    // ---------------------------------------------------------------- housekeeping API

    /** Call regularly (for example every 250 ms) so stale items expire. */
    @Synchronized
    fun tick() = housekeeping(clock.nowMs())

    @Synchronized
    fun queuedCount(): Int = queue.size

    // ---------------------------------------------------------------- pipeline

    private fun process(c: Candidate, now: Long): DecisionRecord {
        housekeeping(now)
        val ctx = context

        // 1. Unrequested INFO only when the user is idle.
        if (c.level == Priority.INFO && !c.userRequested && (ctx.moving || ctx.navigationActive || ctx.sosActive)) {
            return log(now, c, Outcome.SUPPRESSED, Reason.INFO_NOT_REQUESTED, "moving=${ctx.moving} navigation=${ctx.navigationActive} sos=${ctx.sosActive}")
        }

        // 2. Cooldown. Asked-for, closer or more severe repeats bypass it.
        val prev = lastSpoken[c.key]
        var bypass = c.userRequested
        if (!bypass && prev != null) {
            val closer = c.band != DistanceBand.UNKNOWN && c.band.rank < prev.band.rank
            val moreSevere = c.level > prev.level
            bypass = closer || moreSevere
            val cooldown = config.cooldownFor(c.level)
            if (!bypass && now - prev.timeMs < cooldown) {
                return log(now, c, Outcome.SUPPRESSED, Reason.COOLDOWN, "spoken ${now - prev.timeMs} ms ago, cooldown $cooldown ms")
            }
        }

        // 3. Duplicate text.
        val norm = normalize(c.text)
        if (!c.userRequested) {
            val waiting = queue.firstOrNull { normalize(it.c.text) == norm }
            if (waiting != null) {
                if (c.level > waiting.c.level) {
                    queue.remove(waiting)
                    log(now, waiting.c, Outcome.DROPPED, Reason.SUPERSEDED, "replaced by a more severe duplicate")
                } else {
                    return log(now, c, Outcome.SUPPRESSED, Reason.DUPLICATE, "same text already waiting")
                }
            }
            val current = speaking
            if (current != null && normalize(current.c.text) == norm && !bypass) {
                return log(now, c, Outcome.SUPPRESSED, Reason.DUPLICATE, "same text is being spoken")
            }
            val last = recentTexts[norm]
            if (!bypass && last != null && now - last < config.duplicateWindowMs) {
                return log(now, c, Outcome.SUPPRESSED, Reason.DUPLICATE, "same text ${now - last} ms ago")
            }
        }

        // 4. Schedule.
        val cur = speaking
        return when {
            c.level == Priority.CRITICAL && cur != null && cur.c.interruptible -> {
                preempt(cur, now)
                speakNow(c, now, interrupt = true, fromQueue = false)
            }
            c.level == Priority.CRITICAL && cur == null -> speakNow(c, now, interrupt = false, fromQueue = false)
            cur == null && !ctx.listening -> speakNow(c, now, interrupt = false, fromQueue = false)
            else -> enqueue(c, now, queueSeq++)
        }
    }

    private fun preempt(cur: Speaking, now: Long) {
        if (cur.c.userRequested) {
            // The user asked for it: put the unfinished part back at the front.
            queue.add(Pending(cur.c, now, frontSeq--))
            log(now, cur.c, Outcome.QUEUED, Reason.PREEMPTED, "interrupted by a critical alert, will resume")
        } else {
            log(now, cur.c, Outcome.INTERRUPTED, Reason.PREEMPTED, "interrupted by a critical alert")
        }
        speaking = null
    }

    private fun speakNow(c: Candidate, now: Long, interrupt: Boolean, fromQueue: Boolean): DecisionRecord {
        val id = "u" + (++utteranceCounter)
        speaking = Speaking(c, id, now)
        lastSpoken[c.key] = Spoken(now, c.level, c.band)
        recentTexts[normalize(c.text)] = now
        if (interrupt) output.stop()
        output.speak(SpeechRequest(id, c.text, c.level, c.source, c.interruptible, c.eventId))
        val outcome = when {
            fromQueue -> Outcome.SPOKEN_FROM_QUEUE
            interrupt -> Outcome.SPOKEN_INTERRUPT
            else -> Outcome.SPOKEN_NOW
        }
        return log(now, c, outcome, Reason.OK)
    }

    private fun enqueue(c: Candidate, now: Long, seq: Long): DecisionRecord {
        queue.add(Pending(c, now, seq))
        sortQueue()
        var result = log(now, c, Outcome.QUEUED, Reason.WAITING_FOR_GAP)
        // Limit only what the user did not ask for.
        while (queue.count { !it.c.userRequested } > config.maxQueuedAutomatic) {
            val victim = queue.last { !it.c.userRequested }
            queue.remove(victim)
            val dropped = log(now, victim.c, Outcome.DROPPED, Reason.QUEUE_FULL, "queue holds ${config.maxQueuedAutomatic} automatic items")
            if (victim.c === c) result = dropped
        }
        return result
    }

    private fun housekeeping(now: Long) {
        val s = speaking
        if (s != null && now - s.sinceMs > config.maxSpeechMs) speaking = null // lost callback: do not stay silent forever
        val it = queue.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (now - p.enqueuedMs > p.c.ttlMs) {
                it.remove()
                log(now, p.c, Outcome.EXPIRED, Reason.TTL_EXPIRED, "waited longer than ${p.c.ttlMs} ms")
            }
        }
        if (speaking == null && queue.isNotEmpty()) {
            sortQueue()
            val first = queue[0]
            // While the user is being listened to, hold everything except critical alerts.
            if (!context.listening || first.c.level == Priority.CRITICAL) {
                queue.removeAt(0)
                speakNow(first.c, now, interrupt = false, fromQueue = true)
            }
        }
    }

    private fun sortQueue() {
        queue.sortWith(
            compareByDescending<Pending> { it.c.level }
                .thenByDescending { it.c.score }
                .thenBy { it.seq },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun normalize(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun log(now: Long, c: Candidate, outcome: Outcome, reason: Reason, detail: String = ""): DecisionRecord =
        write(now, c.id, c.source, c.kind, c.type, c.display, c.level, c.score, outcome, reason, detail)

    private fun write(
        now: Long, id: String, source: String, kind: String, type: String, display: String,
        level: Priority?, score: Double?, outcome: Outcome, reason: Reason, detail: String,
    ): DecisionRecord {
        val r = DecisionRecord(log.nextSeq(), now, id, source, kind, type, display, level, score, outcome, reason, detail)
        log.add(r)
        return r
    }
}
