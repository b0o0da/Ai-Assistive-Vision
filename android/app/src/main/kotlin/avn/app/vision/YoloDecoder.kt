package avn.app.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Pure YOLOv8-output decode + per-class NMS, split out of [YoloV8OnnxDetector] so it can be unit
 * tested on the host JVM without an Android `Bitmap`/`OnnxTensor` or a device: it only takes plain
 * arrays, floats and a [DetectorConfig], and its caller still owns the ONNX session and letterbox.
 */
internal object YoloDecoder {
    /**
     * Output is `[4+N, anchors]`: xywh followed by N independent per-class scores, in exactly
     * [DetectorConfig.labels]'s order -- N is 80 for the stock COCO model (today's default), but
     * a fine-tuned export may have fewer/reordered classes, so this never hardcodes [CocoLabels].
     */
    fun decode(
        output: Array<FloatArray>,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long,
        scale: Float,
        padX: Float,
        padY: Float,
        config: DetectorConfig
    ): List<Detection> {
        if (output.size < 5 || output[0].isEmpty()) return emptyList()
        val candidates = ArrayList<Detection>()
        for (anchor in output[0].indices) {
            var classId = -1
            var score = 0f
            for (index in 4 until min(output.size, config.labels.size + 4)) {
                if (output[index][anchor] > score) {
                    score = output[index][anchor]
                    classId = index - 4
                }
            }
            if (score < config.confidenceThreshold || classId < 0) continue
            val rawLabel = config.labels.getOrNull(classId) ?: continue
            val label = config.classMap[rawLabel] ?: rawLabel
            if (label !in config.classes) continue

            val centerX = output[0][anchor]
            val centerY = output[1][anchor]
            val width = output[2][anchor]
            val height = output[3][anchor]
            val left = ((centerX - width / 2f - padX) / scale).coerceIn(0f, frameWidth.toFloat())
            val top = ((centerY - height / 2f - padY) / scale).coerceIn(0f, frameHeight.toFloat())
            val right = ((centerX + width / 2f - padX) / scale).coerceIn(0f, frameWidth.toFloat())
            val bottom = ((centerY + height / 2f - padY) / scale).coerceIn(0f, frameHeight.toFloat())
            if (right <= left || bottom <= top) continue
            candidates += Detection(
                label = label,
                confidence = score,
                x = left,
                y = top,
                w = right - left,
                h = bottom - top,
                timestampMs = timestampMs
            )
        }
        return nonMaximumSuppression(candidates, config)
    }

    fun nonMaximumSuppression(input: List<Detection>, config: DetectorConfig): List<Detection> {
        val kept = ArrayList<Detection>()
        for (label in config.classes) {
            val candidates = input.asSequence()
                .filter { it.label == label }
                .sortedByDescending { it.confidence }
                .toMutableList()
            while (candidates.isNotEmpty()) {
                val best = candidates.removeAt(0)
                kept += best
                candidates.removeAll { intersectionOverUnion(best, it) > config.nmsIouThreshold }
            }
        }
        return kept
    }

    fun intersectionOverUnion(a: Detection, b: Detection): Float {
        val overlapWidth = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val overlapHeight = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val overlap = overlapWidth * overlapHeight
        val union = a.w * a.h + b.w * b.h - overlap
        return if (union > 0f) overlap / union else 0f
    }
}
