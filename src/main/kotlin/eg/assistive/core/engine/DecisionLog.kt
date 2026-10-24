package eg.assistive.core.engine

import eg.assistive.core.model.Priority
import java.util.Locale

enum class Outcome { SPOKEN_NOW, SPOKEN_INTERRUPT, SPOKEN_FROM_QUEUE, QUEUED, SUPPRESSED, INTERRUPTED, EXPIRED, DROPPED }

enum class Reason {
    OK, INVALID, LOW_CONFIDENCE, INFO_NOT_REQUESTED, COOLDOWN, DUPLICATE,
    QUEUE_FULL, TTL_EXPIRED, PREEMPTED, SUPERSEDED, WAITING_FOR_GAP,
}

/** One decision. [display] is already redacted for sensitive text. */
data class DecisionRecord(
    val seq: Long,
    val timeMs: Long,
    val candidateId: String,
    val source: String,
    val kind: String,
    val type: String,
    val display: String,
    val priority: Priority?,
    val score: Double?,
    val outcome: Outcome,
    val reason: Reason,
    val detail: String = "",
) {
    val spoken: Boolean
        get() = outcome == Outcome.SPOKEN_NOW || outcome == Outcome.SPOKEN_INTERRUPT || outcome == Outcome.SPOKEN_FROM_QUEUE
}

/** In-memory ring buffer. Nothing is written to disk (privacy default). */
class DecisionLog(private val capacity: Int) {
    private val buffer = ArrayDeque<DecisionRecord>()
    private var seq = 0L

    @Synchronized
    fun nextSeq(): Long = ++seq

    @Synchronized
    fun add(r: DecisionRecord) {
        buffer.addLast(r)
        while (buffer.size > capacity) buffer.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<DecisionRecord> = buffer.toList()
}

/** Read-only alert-log view for the UI, the demo and the final report. */
class AlertLogView(private val log: DecisionLog) {
    fun entries(): List<DecisionRecord> = log.snapshot()
    fun spoken(): List<DecisionRecord> = entries().filter { it.spoken }
    fun suppressed(): List<DecisionRecord> = entries().filter { it.outcome == Outcome.SUPPRESSED }
    fun notSpoken(): List<DecisionRecord> = entries().filter {
        it.outcome == Outcome.SUPPRESSED || it.outcome == Outcome.DROPPED ||
            it.outcome == Outcome.EXPIRED || it.outcome == Outcome.INTERRUPTED
    }

    fun outcomeCounts(): Map<Outcome, Int> = entries().groupingBy { it.outcome }.eachCount()
    fun reasonCounts(): Map<Reason, Int> = entries().filter { it.reason != Reason.OK }.groupingBy { it.reason }.eachCount()

    fun render(limit: Int = 50): String = entries().takeLast(limit).joinToString("\n") { r ->
        String.format(
            Locale.ROOT, "%9.3fs | %-17s | %-18s | %-8s | %-20s | %s",
            r.timeMs / 1000.0, r.outcome, r.reason, r.priority ?: "-", r.type, r.display,
        )
    }
}
