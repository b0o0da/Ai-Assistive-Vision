package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.contracts.SpeechRequest
import avn.engine.intake.EventIntake
import avn.engine.intake.IntakeResult
import avn.engine.scenario.DecisionLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Numbers for [PriorityEngine], loaded from `config/engine.json` (android/CLAUDE.md: never hard-code
 * thresholds). Every value is a **proposed** starting point from architecture.md section 5.
 */
data class EngineConfig(
    val status: String,
    /** Gate 1: per-class minimum confidence; key `default` applies to every other label. */
    val minConfidence: Map<String, Double>,
    val criticalRisk: Int,
    val criticalMinConfidence: Double,
    val warningRisk: Int,
    /** Critical needs the same key seen [persistenceMinHits] times within [persistenceWindowMs]. */
    val persistenceMinHits: Int,
    val persistenceWindowMs: Long,
    /** Crowded scene: this many distinct hazard keys within [crowdWindowMs] raises the Warning floor. */
    val crowdWindowMs: Long,
    val crowdMinDistinctKeys: Int,
    val crowdWarningFloorRisk: Int,
    val decisionLogSize: Int
) {
    fun minConfidenceFor(label: String): Double {
        val l = label.trim().lowercase()
        minConfidence[l]?.let { return it }
        if (l in VEHICLE_LABELS) minConfidence["vehicle"]?.let { return it }
        return minConfidence["default"] ?: 0.0
    }

    companion object {
        val VEHICLE_LABELS =
            setOf("vehicle", "car", "bus", "truck", "motorcycle", "motorbike", "bicycle", "transportation")

        /** Mirrors `config/engine.json`; used only if the resource is missing or unreadable (fail soft). */
        val DEFAULT = EngineConfig(
            status = "PROPOSED",
            minConfidence = mapOf("default" to 0.35, "person" to 0.40, "vehicle" to 0.40),
            criticalRisk = 80,
            criticalMinConfidence = 0.6,
            warningRisk = 45,
            persistenceMinHits = 2,
            persistenceWindowMs = 1_500,
            crowdWindowMs = 3_000,
            crowdMinDistinctKeys = 4,
            crowdWarningFloorRisk = 60,
            decisionLogSize = 500
        )

        fun load(resourcePath: String = "/config/engine.json"): EngineConfig {
            val text = EngineConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching { parse(text) }.getOrDefault(DEFAULT)
        }

        fun parse(text: String): EngineConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            val levels = root["levels"]!!.jsonObject
            val persistence = root["persistence"]!!.jsonObject
            val crowd = root["crowd"]!!.jsonObject
            return EngineConfig(
                status = root["status"]!!.jsonPrimitive.content,
                minConfidence = root["minConfidence"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double },
                criticalRisk = levels["criticalRisk"]!!.jsonPrimitive.int,
                criticalMinConfidence = levels["criticalMinConfidence"]!!.jsonPrimitive.double,
                warningRisk = levels["warningRisk"]!!.jsonPrimitive.int,
                persistenceMinHits = persistence["minHits"]!!.jsonPrimitive.int,
                persistenceWindowMs = persistence["windowMs"]!!.jsonPrimitive.long,
                crowdWindowMs = crowd["windowMs"]!!.jsonPrimitive.long,
                crowdMinDistinctKeys = crowd["minDistinctKeys"]!!.jsonPrimitive.int,
                crowdWarningFloorRisk = crowd["warningFloorRisk"]!!.jsonPrimitive.int,
                decisionLogSize = root["decisionLogSize"]!!.jsonPrimitive.int
            ).also {
                require(it.warningRisk in 0..it.criticalRisk && it.criticalRisk <= 100)
                require(it.persistenceMinHits >= 1 && it.decisionLogSize > 0)
            }
        }
    }
}

/**
 * One row of the alert log (architecture.md section 5, "Logging"): every event produces a record,
 * including events that are never spoken. Privacy: no speech text is stored, and the label is kept
 * only for camera events (a notification or screen label could be private content).
 */
data class DecisionRecord(
    val atMs: Long,
    val id: String,
    val source: Source,
    val type: EventType,
    val label: String?,
    val side: Side?,
    val band: DistanceBand?,
    val confidence: Double?,
    val risk: Int?,
    val level: Level?,
    /** The floor in force when the decision was made, e.g. `warning+ (moving)`. */
    val threshold: String,
    val context: EngineContext,
    val decision: Decision,
    val reason: Reason?,
    /** Set when the item reached the speaker or the queue. */
    val utteranceId: String?,
    /** Free text for decisions no [Reason] covers (e.g. intake rejection). Never contains content. */
    val note: String? = null
) {
    /** One JSON line, the format the repo logs in. */
    fun toJsonLine(): String = buildString {
        val safeId = id.replace('\\', '/').replace('"', '\'')
        append("{\"at_ms\":$atMs,\"id\":\"$safeId\"")
        append(",\"source\":\"${source.name.lowercase()}\",\"type\":\"${type.name.lowercase()}\"")
        label?.let { append(",\"label\":\"${it.replace("\"", "'")}\"") }
        side?.let { append(",\"side\":\"${it.name.lowercase()}\"") }
        band?.let { append(",\"band\":\"${it.name.lowercase()}\"") }
        confidence?.let { append(",\"confidence\":$it") }
        risk?.let { append(",\"risk\":$it") }
        level?.let { append(",\"level\":\"${it.name.lowercase()}\"") }
        append(",\"threshold\":\"$threshold\",\"moving\":${context.moving},\"decision\":\"${decision.wire}\"")
        reason?.let { append(",\"reason\":\"${it.wire}\"") }
        utteranceId?.let { append(",\"utterance_id\":\"$it\"") }
        note?.let { append(",\"note\":\"$it\"") }
        append("}")
    }
}

/**
 * Short spoken phrases for hazards. Hazards are described by side and band only, never in meters,
 * speed or time (android/CLAUDE.md safety rules).
 */
object HazardPhrase {
    fun of(event: Event): String {
        val label = event.label.trim().ifEmpty { "Something" }.replaceFirstChar { it.uppercase() }
        val where = when (event.side) {
            Side.LEFT -> "on your left"
            Side.RIGHT -> "on your right"
            Side.CENTER -> "ahead"
            null -> null
        }
        val distance = when (event.distanceBand) {
            DistanceBand.NEAR -> "close"
            DistanceBand.MEDIUM -> null
            DistanceBand.FAR -> "far"
            null -> null
        }
        val head = when (event.type) {
            EventType.VEHICLE_APPROACHING -> "$label approaching" + (where?.let { " $it" } ?: "")
            else -> label + (where?.let { " $it" } ?: "")
        }
        return if (distance != null) "$head, $distance" else head
    }
}

/**
 * Engine v1 (WS16-03): the one component allowed to cause speech. It assembles the pieces built in
 * WS16-01..07 into the lifecycle of architecture.md section 5:
 *
 * ```
 * Event -> intake -> gate 1 (class min confidence) -> risk -> level
 *       -> gate 2 (current threshold) -> gate 3 (cooldown / duplicate) -> InterruptRules -> SpeechOutput
 * ```
 *
 * - Level: Critical needs risk >= criticalRisk, confidence >= criticalMinConfidence and persistence
 *   (the same key seen minHits times in the window; `vehicle_approaching` is already persistent, the
 *   tracker only emits it after sustained box growth). Without persistence it speaks as Warning.
 *   SOS countdown is always Critical. Non-hazard results (answers, text, screen, notifications,
 *   navigation, notices, faces) are Info: AI output never raises a Critical or Warning.
 * - Gate 2: while moving, camera hazards below Warning are suppressed (`below_threshold`); in a
 *   crowded scene the Warning floor rises so only the top items speak. Navigation is exempt.
 *   Non-hazard Info goes on to the interrupt rules, which hold it until idle or asked.
 * - A user request ([SpeechCandidate.userRequested]) bypasses every threshold for its own answer.
 * - Every event gets a [DecisionRecord] ([decisions], newest last, bounded) and goes to [log].
 *
 * Pure Kotlin, injected [Clock], no Android, no I/O except loading config.
 */
class PriorityEngine(
    private val clock: Clock,
    speaker: SpeechOutput,
    private val config: EngineConfig = EngineConfig.load(),
    private val riskScorer: RiskScorer = RiskScorer(),
    private val cooldownGate: CooldownGate = CooldownGate(clock),
    private val log: DecisionLog? = null
) : EngineInput {
    private val rules = InterruptRules(speaker, clock)
    private var context = EngineContext()
    private val records = ArrayDeque<DecisionRecord>()
    private val keyOfUtterance = HashMap<String, String>()
    private val sightings = HashMap<String, ArrayDeque<Long>>()
    private val recentHazards = ArrayDeque<Pair<Long, String>>()

    /** The alert log, oldest first, at most `decisionLogSize` rows. */
    val decisions: List<DecisionRecord> get() = records.toList()

    val speakingNow: SpeechRequest? get() = rules.speakingNow
    val queued: List<SpeechRequest> get() = rules.queuedRequests

    override fun submit(event: Event) {
        val intake = EventIntake.check(event)
        if (intake is IntakeResult.Rejected) {
            record(event, null, null, "-", Decision.DROPPED, null, null, note = "invalid_event")
            return
        }
        val now = clock.nowMillis()

        // Gate 1: class minimum confidence.
        if (event.confidence < config.minConfidenceFor(event.label)) {
            record(event, null, null, "-", Decision.DROPPED, Reason.LOW_CONFIDENCE, null)
            return
        }

        val hazard = event.isHazard()
        val risk = riskScorer.score(event, approaching = event.type == EventType.VEHICLE_APPROACHING)
        val scored = event.copy(risk = risk)
        val key = DedupKey.of(scored)
        if (hazard) noteSighting(key, now)
        val level = levelFor(scored, key, now)

        // Gate 2: current threshold.
        val threshold = currentThreshold(now)
        if (hazard && context.moving) {
            val floor = if (isCrowded(now)) config.crowdWarningFloorRisk else config.warningRisk
            if (level != Level.CRITICAL && risk < floor) {
                record(scored, risk, level, threshold, Decision.SUPPRESSED, Reason.BELOW_THRESHOLD, null)
                return
            }
        }

        // Gate 3: cooldown / duplicate.
        val gate = cooldownGate.check(scored, level, activeKeys())
        if (gate is GateResult.Suppressed) {
            record(scored, risk, level, threshold, Decision.SUPPRESSED, gate.reason, null)
            return
        }

        val text = if (hazard || event.type == EventType.SOS_COUNTDOWN) HazardPhrase.of(scored) else event.label
        val request = SpeechRequest(
            utteranceId = event.id,
            text = text,
            level = level,
            source = event.source,
            interruptible = level != Level.CRITICAL,
            eventId = event.id
        )
        keyOfUtterance[event.id] = key
        if (keyOfUtterance.size > MAX_TRACKED_UTTERANCES) pruneUtteranceKeys()
        val decision = rules.submit(
            SpeechItem(request, sosCountdown = event.type == EventType.SOS_COUNTDOWN, dedupKey = key)
        )
        record(
            scored,
            risk,
            level,
            threshold,
            decision,
            if (decision ==
                Decision.DROPPED
            ) {
                Reason.QUEUE_OVERFLOW
            } else {
                null
            },
            event.id
        )
    }

    override fun submit(candidate: SpeechCandidate) {
        val id = candidate.eventId ?: "cand_${clock.nowMillis()}"
        val sos = candidate.type == EventType.SOS_COUNTDOWN
        val level = if (sos) Level.CRITICAL else Level.INFO
        val request = SpeechRequest(id, candidate.text, level, candidate.source, !sos, candidate.eventId)
        val decision = rules.submit(
            SpeechItem(request, userRequested = candidate.userRequested || candidate.timeSensitive, sosCountdown = sos)
        )
        val threshold = if (candidate.userRequested) "bypass (asked)" else currentThreshold(clock.nowMillis())
        add(
            DecisionRecord(
                clock.nowMillis(), id, candidate.source, candidate.type, null, null, null, null, null, level,
                threshold, context, decision, if (decision == Decision.DROPPED) Reason.QUEUE_OVERFLOW else null, id
            )
        )
    }

    override fun updateContext(
        listening: Boolean,
        speaking: Boolean,
        moving: Boolean,
        navigationActive: Boolean,
        offline: Boolean,
        sosActive: Boolean
    ) {
        context = EngineContext(listening, speaking, moving, navigationActive, offline, sosActive)
        rules.updateContext(listening, speaking, moving, navigationActive, offline, sosActive)
    }

    /** The app calls this when TTS finishes or is stopped on an utterance: the "gap" the queue waits for. */
    fun onUtteranceFinished(utteranceId: String) {
        rules.notifyFinished(utteranceId)
        keyOfUtterance.remove(utteranceId)
    }

    private fun levelFor(event: Event, key: String, now: Long): Level {
        if (event.type == EventType.SOS_COUNTDOWN) return Level.CRITICAL
        if (!event.isHazard()) return Level.INFO
        val risk = event.risk
        return when {
            risk >= config.criticalRisk &&
                event.confidence >= config.criticalMinConfidence &&
                isPersistent(event, key, now) -> Level.CRITICAL
            risk >= config.warningRisk || risk >= config.criticalRisk -> Level.WARNING
            else -> Level.INFO
        }
    }

    private fun isPersistent(event: Event, key: String, now: Long): Boolean {
        if (event.type == EventType.VEHICLE_APPROACHING) return true
        val seen = sightings[key] ?: return false
        return seen.count { now - it <= config.persistenceWindowMs } >= config.persistenceMinHits
    }

    private fun noteSighting(key: String, now: Long) {
        val list = sightings.getOrPut(key) { ArrayDeque() }
        list.addLast(now)
        while (list.isNotEmpty() && now - list.first() > config.persistenceWindowMs) list.removeFirst()
        sightings.entries.removeIf { (_, v) -> v.isEmpty() || now - v.last() > config.persistenceWindowMs }
        recentHazards.addLast(now to key)
        while (recentHazards.isNotEmpty() && now - recentHazards.first().first > config.crowdWindowMs) {
            recentHazards.removeFirst()
        }
    }

    private fun isCrowded(now: Long): Boolean =
        recentHazards.filter { now - it.first <= config.crowdWindowMs }.map { it.second }.toSet().size >=
            config.crowdMinDistinctKeys

    private fun currentThreshold(now: Long): String = when {
        context.sosActive -> "sos only"
        context.moving && isCrowded(now) -> "warning+ risk>=${config.crowdWarningFloorRisk} (crowded)"
        context.moving -> "warning+ (moving)"
        else -> "info+ (stationary)"
    }

    private fun activeKeys(): Set<String> = buildSet {
        rules.speakingNow?.utteranceId?.let { keyOfUtterance[it] }?.let(::add)
        rules.queuedRequests.forEach { r -> keyOfUtterance[r.utteranceId]?.let(::add) }
    }

    private fun record(
        event: Event,
        risk: Int?,
        level: Level?,
        threshold: String,
        decision: Decision,
        reason: Reason?,
        utteranceId: String?,
        note: String? = null
    ) = add(
        DecisionRecord(
            atMs = clock.nowMillis(),
            id = event.id,
            source = event.source,
            type = event.type,
            label = if (event.source == Source.CAMERA) event.label else null,
            side = event.side,
            band = event.distanceBand,
            confidence = event.confidence,
            risk = risk,
            level = level,
            threshold = threshold,
            context = context,
            decision = decision,
            reason = reason,
            utteranceId = utteranceId,
            note = note
        )
    )

    private fun add(record: DecisionRecord) {
        records.addLast(record)
        while (records.size > config.decisionLogSize) records.removeFirst()
        log?.record(record.id, record.decision, record.reason)
    }

    private fun Event.isHazard(): Boolean = type in HAZARD_TYPES && source == Source.CAMERA

    /** Keeps only keys of utterances still speaking, queued or held, so the map stays bounded. */
    private fun pruneUtteranceKeys() {
        val live = buildSet {
            rules.speakingNow?.let { add(it.utteranceId) }
            rules.queuedRequests.forEach { add(it.utteranceId) }
            rules.heldRequests.forEach { add(it.utteranceId) }
        }
        keyOfUtterance.keys.retainAll(live)
    }

    companion object {
        private const val MAX_TRACKED_UTTERANCES = 256

        /** Camera hazards; everything else is a result or notice and can never be Critical or Warning. */
        val HAZARD_TYPES = setOf(EventType.VEHICLE_APPROACHING, EventType.OBSTACLE_AHEAD, EventType.PERSON_CLOSE)
    }
}
