package avn.engine

import avn.contracts.Level
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Numbers for [SpeechQueue], loaded from `config/queue.json` on the classpath -- android/CLAUDE.md:
 * "Engine numbers... never hard-code thresholds in code." Parsed by hand through
 * `kotlinx.serialization.json.JsonElement` rather than `Json.decodeFromString`: there is no reason
 * to add the `kotlin.serialization` compiler plugin to `:engine` for one small config file, and
 * AVN-40 already found that plugin fights Hilt's KSP step elsewhere in this project, so the
 * manual-parse pattern is kept consistent across modules that only need to read JSON, not model it.
 *
 * [maxDepth], [warningExpiryMs] and [infoExpiryMs] are the spec's **proposed** starting values
 * (architecture.md WS-16 deep dive: "Maximum depth 3"; AVN-56: "Expire Warning after about 3 s,
 * Info after about 10 s"). Critical has no entry in the config and no expiry here -- "Critical
 * never dropped" is a safety invariant, not a tunable number, so [SpeechQueue] never looks one up
 * for it (see [expiryMsFor]).
 */
data class SpeechQueueConfig(val maxDepth: Int, val warningExpiryMs: Long, val infoExpiryMs: Long) {
    /** Milliseconds after which a queued request of [level] goes stale, or null if it never does
     * (Critical only). */
    fun expiryMsFor(level: Level): Long? = when (level) {
        Level.CRITICAL -> null
        Level.WARNING -> warningExpiryMs
        Level.INFO -> infoExpiryMs
    }

    companion object {
        /** Mirrors `config/queue.json`. Used only if the resource is missing -- the repo's
         * fail-soft rule: a missing config file must never crash the engine. */
        val DEFAULT = SpeechQueueConfig(maxDepth = 3, warningExpiryMs = 3000L, infoExpiryMs = 10_000L)

        fun load(resourcePath: String = "/config/queue.json"): SpeechQueueConfig {
            val text = SpeechQueueConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: return DEFAULT
            val root = Json.parseToJsonElement(text).jsonObject
            val expiry = root["expiryMs"]!!.jsonObject
            return SpeechQueueConfig(
                maxDepth = root["maxDepth"]!!.jsonPrimitive.int,
                warningExpiryMs = expiry["warning"]!!.jsonPrimitive.long,
                infoExpiryMs = expiry["info"]!!.jsonPrimitive.long
            )
        }
    }
}
