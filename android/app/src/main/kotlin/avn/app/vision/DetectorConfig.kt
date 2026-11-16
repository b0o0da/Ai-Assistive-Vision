package avn.app.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DetectorConfig(
    val modelAsset: String,
    val inputSize: Int,
    val confidenceThreshold: Float,
    val nmsIouThreshold: Float,
    val classes: Set<String>,
    /**
     * Exact, ordered class list the model's output tensor emits (row `4 + i` of `[1, 4+N, anchors]`
     * is `labels[i]`'s score). Must always match whatever `.onnx` is actually shipped in
     * `android/app/src/main/assets/` -- this [DEFAULT] is a fallback for when `detector.json`
     * fails to load, so it has to agree with that same asset, never with a different (e.g. older
     * stock COCO) order, or a fallback load would silently mis-decode every detection.
     */
    val labels: List<String> = CocoLabels.names,
    /**
     * Optional raw-label -> display-label remap, applied by [YoloDecoder] before the [classes]
     * filter. Lets a single model's many raw classes collapse into one broader display category
     * (e.g. stock COCO's "bicycle"/"car"/"motorcycle"/"bus"/"truck" -> "transportation") entirely
     * in software, with no retraining and no accuracy loss from fine-tuning on a small sample --
     * see docs/decisions/ (model-accuracy note, 2026-11-14) for why this replaced the earlier
     * from-scratch fine-tuned `GENERAL_CLASSES` checkpoint. A raw label absent from this map
     * passes through unchanged (e.g. "person" -> "person").
     */
    val classMap: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * The general/everyday-object detector as of 2026-11-14: stock, un-fine-tuned Ultralytics
         * YOLO11m COCO weights (full 80-class accuracy, no small-sample fine-tuning degradation),
         * dynamically INT8-quantized (`yolo11m_int8_coco_stock.onnx`, onnxruntime.quantization)
         * to recover real-time speed, with vehicle body types merged into one "transportation"
         * display class via [classMap] -- this app's hazard logic only ever needs
         * `near|medium|far` + category, never vehicle body type.
         *
         * Progression tried, measured on this repo's dev machine (desktop CPU, onnxruntime):
         * yolov8n ~31ms (COCO mAP50-95 37.3) -> yolov8s ~90ms (44.9) -> yolov8m fp32 ~230ms (50.2,
         * rejected: too slow for a real-time hazard alert) -> yolov8l fp32 ~478ms (52.9, clearly
         * unusable) -> yolov8m INT8 ~108ms (kept m's accuracy at near-yolov8s speed) -> YOLO11m
         * INT8 ~112ms (Ultralytics' newer architecture: published mAP50-95 51.5 vs yolov8m's 50.2,
         * at a smaller file size -- 20.6 MB vs 26.3 MB quantized -- and the same speed, so it
         * strictly supersedes the yolov8m INT8 choice). A single real-image spot check after
         * quantization was mixed per-class (expected at n=1: e.g. this model's "dining table"
         * confidence was lower, "person" higher) -- not proof of an overall win, only that nothing
         * broke; the published benchmark is the actual basis for preferring it.
         *
         * On-device CPU latency (real ARM64-v8a emulator, 2026-11-15, ~333ms mean combined with
         * the pothole model) confirms onnxruntime-android's kernel set covers this quantization's
         * ops end-to-end -- see computer_vision/eval/map_eval_report.md. Real NPU/NNAPI delegate
         * acceleration on physical silicon is still unverified (no physical device available).
         *
         * Pothole detection is a SEPARATE model/config -- see [POTHOLE_DEFAULT] and
         * `detector_pothole.json` -- because COCO has no pothole class at all; this config's
         * `classes` intentionally excludes it.
         */
        val DEFAULT = DetectorConfig(
            modelAsset = "yolo11m_int8_coco_stock.onnx",
            inputSize = 640,
            confidenceThreshold = 0.35f,
            nmsIouThreshold = 0.50f,
            classes = setOf(
                "person", "transportation",
                "bench", "chair", "fire hydrant", "stop sign", "parking meter"
            ),
            labels = CocoLabels.names,
            classMap = mapOf(
                "bicycle" to "transportation",
                "car" to "transportation",
                "motorcycle" to "transportation",
                "bus" to "transportation",
                "truck" to "transportation"
            )
        )

        /**
         * Fallback for the dedicated pothole detector (`detector_pothole.json`,
         * `pothole_keremberke_n.onnx`): a single-class checkpoint from
         * `keremberke/yolov8n-pothole-segmentation` (Hugging Face; trained on the same
         * `keremberke/pothole-segmentation` CC BY 4.0 data as `datasets/data/detection/
         * public_sources/hf_pothole_keremberke/`, same license posture as the rest of this repo's
         * YOLOv8-derived weights -- see models/manifest.json). It is a segmentation checkpoint;
         * its ONNX export's output0 has extra mask-coefficient rows after the 1 class score, which
         * [YoloDecoder] never reads (it only reads rows `4 until 4 + labels.size`).
         */
        val POTHOLE_DEFAULT = DetectorConfig(
            modelAsset = "pothole_keremberke_n.onnx",
            inputSize = 640,
            confidenceThreshold = 0.25f,
            nmsIouThreshold = 0.45f,
            classes = setOf("pothole"),
            labels = listOf("pothole")
        )

        fun load(resourcePath: String = "/config/detector.json"): DetectorConfig =
            load(resourcePath, DEFAULT)

        fun load(resourcePath: String, fallback: DetectorConfig): DetectorConfig {
            val text = DetectorConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return fallback
            return runCatching {
                val values = Json.parseToJsonElement(text).jsonObject
                DetectorConfig(
                    modelAsset = values.getValue("modelAsset").jsonPrimitive.content,
                    inputSize = values.getValue("inputSize").jsonPrimitive.int,
                    confidenceThreshold = values.getValue("confidenceThreshold").jsonPrimitive.float,
                    nmsIouThreshold = values.getValue("nmsIouThreshold").jsonPrimitive.float,
                    classes = values.getValue("classes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    labels = values["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: CocoLabels.names,
                    classMap = values["classMap"]?.jsonObject
                        ?.mapValues { it.value.jsonPrimitive.content }
                        ?: emptyMap()
                ).validated()
            }.getOrDefault(fallback)
        }
    }

    private fun validated(): DetectorConfig {
        require(modelAsset.isNotBlank() && inputSize > 0)
        require(confidenceThreshold in 0f..1f && nmsIouThreshold in 0f..1f)
        require(labels.isNotEmpty() && labels.toSet().size == labels.size)
        require(classMap.keys.all { it in labels }) { "classMap key not in labels" }
        // A class is reachable either directly (a raw label with no remap) or via classMap's
        // display-name values (a raw label collapsed into a broader category).
        val reachable = labels.toSet() + classMap.values
        require(classes.isNotEmpty() && classes.all { it in reachable }) { "unreachable class" }
        return this
    }
}
