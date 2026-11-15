package avn.engine

import avn.contracts.Event
import avn.contracts.SpeechRequest

/**
 * What the engine keeps in its speech queue (WS16-01).
 *
 * [request] is the frozen Gate 1 contract type that goes to SpeechOutput unchanged.
 * [dedupKey] and the expiry time are engine-internal, so they live here and not in SpeechRequest.
 * The ttl value always comes from engine_config.json (WS16-04/05); nothing here hard-codes one.
 */
data class SpeechItem(
    val request: SpeechRequest,
    val dedupKey: String,
    val createdAtMs: Long,
    val ttlMs: Long
) {
    init {
        require(dedupKey.isNotBlank()) { "dedupKey must not be blank" }
        require(createdAtMs >= 0) { "createdAtMs must not be negative" }
        require(ttlMs > 0) { "ttlMs must be positive" }
    }

    val expiresAtMs: Long get() = createdAtMs + ttlMs

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/** Architecture §5: the cooldown and duplicate key is type + label + side + band. */
object DedupKey {
    private const val NONE = "-"

    fun of(event: Event): String = listOf(
        event.type.name,
        event.label.trim(),
        event.side?.name ?: NONE,
        event.distanceBand?.name ?: NONE
    ).joinToString("|").lowercase()
}
