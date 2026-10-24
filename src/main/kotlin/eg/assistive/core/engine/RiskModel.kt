package eg.assistive.core.engine

import eg.assistive.core.model.DistanceBand
import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.Side

data class Assessment(val score: Double, val level: Priority)

/**
 * event -> risk score (0..1) -> threshold -> level.
 *
 *   score = clamp(base(type) + distance + side) * (0.5 + 0.5 * confidence)
 *   level = CRITICAL if score >= 0.80, WARNING if score >= 0.40, else INFO
 *
 * A missing distance, side or confidence adds nothing and removes nothing, so incomplete data never
 * downgrades a danger. The producer's `risk` hint can only RAISE the level (it sets a floor).
 * All numbers are starter values: tune them on recordings (Weeks 17-20).
 */
object RiskModel {
    const val SOS = "sos"
    const val VEHICLE = "vehicle_approaching"

    val baseRisk: Map<String, Double> = mapOf(
        SOS to 1.00,
        VEHICLE to 0.85,
        "person_too_close" to 0.60,
        "obstacle_ahead" to 0.55,
        "obstacle" to 0.55, // name used in the project plan; accepted as an alias
        "text_detected" to 0.15,
        "scene_description" to 0.10,
        "notification" to 0.10,
        "currency_detected" to 0.10,
        "face_known" to 0.10,
        "face_unknown" to 0.10,
    )
    const val UNKNOWN_TYPE_RISK = 0.10

    /** Null if valid, otherwise the reason. */
    fun validate(e: Event): String? {
        if (e.id.isBlank()) return "missing id"
        if (e.type.isBlank()) return "missing type"
        val c = e.confidence
        if (c != null && c !in 0.0..1.0) return "confidence must be between 0 and 1"
        return null
    }

    fun score(e: Event): Double {
        if (e.type == SOS) return 1.0
        val base = baseRisk[e.type] ?: UNKNOWN_TYPE_RISK
        val distance = when (e.distanceBand) {
            DistanceBand.NEAR -> 0.15
            DistanceBand.MEDIUM -> 0.05
            DistanceBand.FAR -> -0.10
            DistanceBand.UNKNOWN -> 0.0
        }
        val side = if (e.side == Side.CENTER) 0.05 else 0.0
        val raw = (base + distance + side).coerceIn(0.0, 1.0)
        val confidenceFactor = e.confidence?.let { 0.5 + 0.5 * it } ?: 1.0
        return Math.round(raw * confidenceFactor * 1000) / 1000.0
    }

    fun assess(e: Event, cfg: EngineConfig): Assessment {
        var s = score(e)
        when (e.risk) {
            Priority.CRITICAL -> s = maxOf(s, cfg.criticalThreshold)
            Priority.WARNING -> s = maxOf(s, cfg.warningThreshold)
            else -> {}
        }
        val level = when {
            s >= cfg.criticalThreshold -> Priority.CRITICAL
            s >= cfg.warningThreshold -> Priority.WARNING
            else -> Priority.INFO
        }
        return Assessment(s, level)
    }

    /** Fixed scores for speech that did not come from an event, so queue ordering stays comparable. */
    fun levelScore(p: Priority): Double = when (p) {
        Priority.CRITICAL -> 0.90
        Priority.WARNING -> 0.60
        Priority.INFO -> 0.20
    }

    fun phrase(e: Event): String =
        e.label.ifBlank { e.type.replace('_', ' ').replaceFirstChar { it.uppercase() } }
}
