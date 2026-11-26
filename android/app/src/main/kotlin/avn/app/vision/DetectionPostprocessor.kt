package avn.app.vision

import avn.app.camera.Frame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Numbers and label lists for [DetectionPostprocessor], from `config/postprocess.json` (PROPOSED). */
data class PostprocessConfig(
    val specLabels: Set<String>,
    val classMap: Map<String, String>,
    val minConfidence: Map<String, Float>,
    val nmsIouThreshold: Float,
    val maxDetections: Int
) {
    fun thresholdFor(label: String): Float = minConfidence[label] ?: minConfidence["default"] ?: 0f

    companion object {
        val DEFAULT = PostprocessConfig(
            specLabels = setOf(
                "person",
                "transportation",
                "bench",
                "chair",
                "fire hydrant",
                "stop sign",
                "parking meter",
                "pothole"
            ),
            classMap = mapOf(
                "bicycle" to "transportation",
                "car" to "transportation",
                "motorcycle" to "transportation",
                "bus" to "transportation",
                "truck" to "transportation",
                "vehicle" to "transportation"
            ),
            minConfidence = mapOf("default" to 0.35f, "person" to 0.40f, "transportation" to 0.40f, "pothole" to 0.30f),
            nmsIouThreshold = 0.5f,
            maxDetections = 50
        )

        fun load(resourcePath: String = "/config/postprocess.json"): PostprocessConfig {
            val text = PostprocessConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching { parse(text) }.getOrDefault(DEFAULT)
        }

        fun parse(text: String): PostprocessConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            return PostprocessConfig(
                specLabels = root.getValue("specLabels").jsonArray.map { it.jsonPrimitive.content.lowercase() }.toSet(),
                classMap = root["classMap"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content.lowercase() }
                    ?.mapKeys { it.key.lowercase() } ?: emptyMap(),
                minConfidence = root.getValue("minConfidence").jsonObject
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.jsonPrimitive.float },
                nmsIouThreshold = root.getValue("nmsIouThreshold").jsonPrimitive.float,
                maxDetections = root.getValue("maxDetections").jsonPrimitive.int
            ).also {
                require(it.nmsIouThreshold in 0f..1f && it.maxDetections > 0)
                require(it.minConfidence.values.all { v -> v in 0f..1f })
            }
        }
    }
}

/**
 * Detector-agnostic post-processing (WS05-07), applied to whatever any [Detector] returns
 * (ONNX, TFLite or the fake replaying raw fixtures), in this order:
 * 1. map raw labels to display labels (`car` -> `transportation`), lower-case;
 * 2. drop every label that is not a spec label (the app never speaks about a "cat" or "tv");
 * 3. drop boxes under their class's minimum confidence (per-class, from config);
 * 4. drop degenerate boxes (zero or negative size, NaN);
 * 5. per-class non-maximum suppression, highest confidence first;
 * 6. keep at most `maxDetections`, highest confidence first.
 * Pure: no Android, no state, safe to call from any thread.
 */
class DetectionPostprocessor(private val config: PostprocessConfig = PostprocessConfig.load()) {
    fun apply(raw: List<Detection>): List<Detection> {
        val candidates = raw.asSequence()
            .map { d -> d.copy(label = mapLabel(d.label)) }
            .filter { it.label in config.specLabels }
            .filter { it.confidence.isFinite() && it.confidence >= config.thresholdFor(it.label) }
            .filter { it.w > 0f && it.h > 0f && it.x.isFinite() && it.y.isFinite() }
            .toList()
        val kept = ArrayList<Detection>()
        for ((_, group) in candidates.groupBy { it.label }) {
            val pending = group.sortedByDescending { it.confidence }.toMutableList()
            while (pending.isNotEmpty()) {
                val best = pending.removeAt(0)
                kept += best
                pending.removeAll { YoloDecoder.intersectionOverUnion(best, it) > config.nmsIouThreshold }
            }
        }
        return kept.sortedByDescending { it.confidence }.take(config.maxDetections)
    }

    private fun mapLabel(raw: String): String {
        val l = raw.trim().lowercase()
        return config.classMap[l] ?: l
    }
}

/** Wraps any [Detector] so every frame's output goes through [DetectionPostprocessor]. */
class PostprocessingDetector(
    private val inner: Detector,
    private val post: DetectionPostprocessor = DetectionPostprocessor()
) : Detector {
    override suspend fun detect(frame: Frame): List<Detection> = post.apply(inner.detect(frame))
}
