package avn.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Values come from docs/architecture.md §5 "Logging". Used by scenario files and the alert log.

@Serializable
enum class Decision {
    @SerialName("spoken")
    SPOKEN,

    @SerialName("queued")
    QUEUED,

    @SerialName("held")
    HELD,

    @SerialName("suppressed")
    SUPPRESSED,

    @SerialName("dropped")
    DROPPED,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("merged")
    MERGED,

    @SerialName("expired")
    EXPIRED
}

@Serializable
enum class Reason {
    @SerialName("low_confidence")
    LOW_CONFIDENCE,

    @SerialName("below_threshold")
    BELOW_THRESHOLD,

    @SerialName("cooldown")
    COOLDOWN,

    @SerialName("duplicate")
    DUPLICATE,

    @SerialName("merged")
    MERGED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("preempted")
    PREEMPTED,

    @SerialName("queue_overflow")
    QUEUE_OVERFLOW
}
