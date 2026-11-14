package avn.app.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Runtime-free tracker and approach thresholds; kept beside bands.json for calibration. */
data class TrackerConfig(
    val highConfidence: Float,
    val lowConfidence: Float,
    val matchIou: Float,
    val maxLostFrames: Int,
    val historySize: Int,
    val approachMinObservations: Int,
    val approachMinSpanMs: Long,
    val approachGrowthRatio: Float,
    val approachGrowthRatioWhileMoving: Float
) {
    companion object {
        val DEFAULT = TrackerConfig(
            highConfidence = 0.45f,
            lowConfidence = 0.10f,
            matchIou = 0.30f,
            maxLostFrames = 8,
            historySize = 6,
            approachMinObservations = 5,
            approachMinSpanMs = 350,
            approachGrowthRatio = 1.35f,
            approachGrowthRatioWhileMoving = 1.65f
        )

        fun load(resourcePath: String = "/config/tracker.json"): TrackerConfig {
            val text = TrackerConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching {
                val values = Json.parseToJsonElement(text).jsonObject
                TrackerConfig(
                    highConfidence = values.getValue("highConfidence").jsonPrimitive.float,
                    lowConfidence = values.getValue("lowConfidence").jsonPrimitive.float,
                    matchIou = values.getValue("matchIou").jsonPrimitive.float,
                    maxLostFrames = values.getValue("maxLostFrames").jsonPrimitive.int,
                    historySize = values.getValue("historySize").jsonPrimitive.int,
                    approachMinObservations = values.getValue("approachMinObservations").jsonPrimitive.int,
                    approachMinSpanMs = values.getValue("approachMinSpanMs").jsonPrimitive.content.toLong(),
                    approachGrowthRatio = values.getValue("approachGrowthRatio").jsonPrimitive.float,
                    approachGrowthRatioWhileMoving = values.getValue("approachGrowthRatioWhileMoving").jsonPrimitive.float
                ).validated()
            }.getOrDefault(DEFAULT)
        }
    }

    private fun validated(): TrackerConfig {
        require(lowConfidence in 0f..highConfidence && highConfidence <= 1f)
        require(matchIou in 0f..1f && maxLostFrames >= 0 && historySize >= approachMinObservations)
        require(approachMinObservations >= 3 && approachMinSpanMs > 0)
        require(approachGrowthRatio > 1f && approachGrowthRatioWhileMoving >= approachGrowthRatio)
        return this
    }
}
