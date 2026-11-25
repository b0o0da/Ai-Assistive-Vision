package avn.engine

import avn.contracts.Event
import avn.contracts.Level
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Per-level cooldowns for [CooldownGate], loaded from `config/cooldowns.json`. The values are the
 * **proposed** starting numbers from architecture.md section 5 (Critical 3 s, Warning 8 s, Info 30 s)
 * and the file says so in its `status` field. Parsed by hand, like [SpeechQueueConfig].
 */
data class CooldownConfig(val status: String, val criticalMs: Long, val warningMs: Long, val infoMs: Long) {
    fun cooldownMsFor(level: Level): Long = when (level) {
        Level.CRITICAL -> criticalMs
        Level.WARNING -> warningMs
        Level.INFO -> infoMs
    }

    val longestMs: Long get() = maxOf(criticalMs, warningMs, infoMs)

    companion object {
        /** Mirrors `config/cooldowns.json`; used only if the resource is missing or unreadable (fail soft). */
        val DEFAULT = CooldownConfig(status = "PROPOSED", criticalMs = 3_000L, warningMs = 8_000L, infoMs = 30_000L)

        fun load(resourcePath: String = "/config/cooldowns.json"): CooldownConfig {
            val text = CooldownConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: return DEFAULT
            return runCatching { parse(text) }.getOrDefault(DEFAULT)
        }

        fun parse(text: String): CooldownConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            val ms = root["cooldownMs"]!!.jsonObject
            return CooldownConfig(
                status = root["status"]!!.jsonPrimitive.content,
                criticalMs = ms["critical"]!!.jsonPrimitive.long,
                warningMs = ms["warning"]!!.jsonPrimitive.long,
                infoMs = ms["info"]!!.jsonPrimitive.long
            )
        }
    }
}

/** What [CooldownGate.check] decided. */
sealed interface GateResult {
    /** Let the event through; its key's cooldown timer now starts. */
    data object Pass : GateResult

    /** Do not speak. [reason] is [Reason.DUPLICATE] or [Reason.COOLDOWN]; log it with [Decision.SUPPRESSED]. */
    data class Suppressed(val reason: Reason) : GateResult
}

/**
 * Gate 3 of the engine lifecycle (WS16-05, architecture.md section 5): cooldown and duplicate suppression.
 *
 * - The key is [DedupKey.of]: type + label + side + band. A band change (or side change) is a new key,
 *   so it is never held back by the old key's cooldown ("band change bypasses dedup").
 * - Duplicate: a key that is speaking now or already queued ([activeKeys]) is suppressed with
 *   [Reason.DUPLICATE]. The caller owns the speaker and the queue, so it passes their keys in.
 * - Cooldown: a key that passed less than its level's cooldown ago is suppressed with [Reason.COOLDOWN].
 *   The cooldown used is the one of the event's own level.
 * - Escalation: if the same key comes back at a more severe level than it last passed with
 *   (for example Warning, then Critical), it passes at once. A Critical is never delayed by a lower level.
 * - Suppressed events do not restart the timer, so a key that keeps firing is spoken again once
 *   per cooldown, not never.
 *
 * Old keys are dropped after the longest cooldown, so memory stays bounded on long walks.
 */
class CooldownGate(private val clock: Clock, private val config: CooldownConfig = CooldownConfig.load()) {
    private data class Passed(val atMs: Long, val level: Level)

    private val lastPassed = HashMap<String, Passed>()

    fun check(event: Event, level: Level, activeKeys: Set<String> = emptySet()): GateResult {
        val now = clock.nowMillis()
        prune(now)
        val key = DedupKey.of(event)
        if (key in activeKeys) return GateResult.Suppressed(Reason.DUPLICATE)
        val last = lastPassed[key]
        if (last != null) {
            val escalated = level.ordinal < last.level.ordinal
            val inCooldown = now - last.atMs < config.cooldownMsFor(level)
            if (inCooldown && !escalated) return GateResult.Suppressed(Reason.COOLDOWN)
        }
        lastPassed[key] = Passed(now, level)
        return GateResult.Pass
    }

    /** Forget every key (for example when the user stops and restarts a walk). */
    fun reset() = lastPassed.clear()

    /** Number of keys remembered; for tests. */
    val trackedKeys: Int get() = lastPassed.size

    private fun prune(now: Long) {
        val horizon = config.longestMs
        lastPassed.entries.removeIf { now - it.value.atMs >= horizon }
    }
}
