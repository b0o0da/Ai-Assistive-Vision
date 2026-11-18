package avn.engine

/*
 * Wire names for the decision log and scenario files (WS16-01). The values come from
 * docs/architecture.md section 5 "Logging". [Decision] itself is declared next to
 * [InterruptRules], which returns it.
 */

/** Lower-case name used in logs and scenario JSON, for example `spoken` or `queue_overflow`. */
val Decision.wire: String get() = name.lowercase()

fun decisionFromWire(value: String): Decision? = Decision.entries.firstOrNull { it.wire == value }

/** Why the engine made a [Decision]. Same names as the `reason` column in architecture.md section 5. */
enum class Reason {
    LOW_CONFIDENCE,
    BELOW_THRESHOLD,
    COOLDOWN,
    DUPLICATE,
    MERGED,
    EXPIRED,
    PREEMPTED,
    QUEUE_OVERFLOW;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): Reason? = entries.firstOrNull { it.wire == value }
    }
}
