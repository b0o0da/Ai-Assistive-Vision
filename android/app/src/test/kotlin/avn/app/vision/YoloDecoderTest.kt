package avn.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YoloDecoder] is the exact decode + per-class NMS logic [YoloV8OnnxDetector] runs on a real
 * ONNX session's `[1,84,8400]` output; it was split out specifically so this logic -- the part
 * that decides whether a real detection becomes a real event -- can run as a plain JVM test with
 * no device, Robolectric or ONNX Runtime native library involved. These tests build small
 * synthetic `[84, N]` outputs by hand (anchor count N varies per test; the real model emits 8400,
 * but the decode loop only cares about the shape's row semantics, not a specific anchor count).
 */
class YoloDecoderTest {
    // An explicit fixture, independent of DetectorConfig.DEFAULT (which tracks whatever model is
    // actually shipped, e.g. GENERAL_CLASSES as of 2026-11-14): these tests exercise YoloDecoder's
    // generic decode/NMS logic against the full stock COCO-80 label order, not a specific model's
    // taxonomy, and must keep working regardless of which model DEFAULT points at.
    private val config = DetectorConfig(
        modelAsset = "test-fixture.onnx",
        inputSize = 640,
        confidenceThreshold = 0.45f,
        nmsIouThreshold = 0.50f,
        classes = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "bench", "chair", "fire hydrant", "stop sign", "parking meter"
        ),
        labels = CocoLabels.names
    ) // classes include "person", "car"; exclude "dog".

    /** One column per anchor: [cx, cy, w, h, classScore0, classScore1, ...] for all 80 classes. */
    private fun output(vararg anchors: FloatArray): Array<FloatArray> {
        val rows = 4 + CocoLabels.names.size
        return Array(rows) { row -> FloatArray(anchors.size) { col -> anchors[col][row] } }
    }

    private fun anchor(cx: Float, cy: Float, w: Float, h: Float, classId: Int, score: Float): FloatArray {
        val row = FloatArray(4 + CocoLabels.names.size)
        row[0] = cx; row[1] = cy; row[2] = w; row[3] = h
        row[4 + classId] = score
        return row
    }

    private val personId = CocoLabels.names.indexOf("person")
    private val carId = CocoLabels.names.indexOf("car")
    private val dogId = CocoLabels.names.indexOf("dog") // not in DetectorConfig.DEFAULT.classes

    @Test
    fun aHighConfidenceAnchorInIdentityLetterboxSpaceDecodesToTheExpectedBox() {
        val out = output(anchor(cx = 100f, cy = 100f, w = 40f, h = 60f, classId = personId, score = 0.9f))
        val detections = YoloDecoder.decode(
            out, frameWidth = 640, frameHeight = 640, timestampMs = 123L,
            scale = 1f, padX = 0f, padY = 0f, config = config
        )
        assertEquals(1, detections.size)
        val detection = detections.single()
        assertEquals("person", detection.label)
        assertEquals(0.9f, detection.confidence, 0.0001f)
        assertEquals(80f, detection.x, 0.01f) // 100 - 40/2
        assertEquals(70f, detection.y, 0.01f) // 100 - 60/2
        assertEquals(40f, detection.w, 0.01f)
        assertEquals(60f, detection.h, 0.01f)
        assertEquals(123L, detection.timestampMs)
    }

    @Test
    fun anAnchorBelowTheConfidenceThresholdIsDropped() {
        val out = output(anchor(100f, 100f, 40f, 60f, personId, score = config.confidenceThreshold - 0.01f))
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun anAnchorOfAClassNotInTheConfiguredSetIsDropped() {
        // "dog" scores highest for this anchor but is not in config.classes.
        val out = output(anchor(100f, 100f, 40f, 60f, dogId, score = 0.95f))
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun theLetterboxInverseTransformMapsBackToOriginalFrameCoordinates() {
        // A 1280x640 frame letterboxed into 640x640: scale=0.5, padX=0, padY=160.
        // Anchor center/size are in 640-space; expected box is in original 1280x640-space.
        val out = output(anchor(cx = 320f, cy = 320f, w = 100f, h = 80f, classId = personId, score = 0.9f))
        val detections = YoloDecoder.decode(
            out, frameWidth = 1280, frameHeight = 640, timestampMs = 0L,
            scale = 0.5f, padX = 0f, padY = 160f, config = config
        )
        val detection = detections.single()
        // left = (320 - 50 - 0) / 0.5 = 540 ; top = (320 - 40 - 160) / 0.5 = 240
        assertEquals(540f, detection.x, 0.01f)
        assertEquals(240f, detection.y, 0.01f)
        assertEquals(200f, detection.w, 0.01f) // 100 / 0.5
        assertEquals(160f, detection.h, 0.01f) // 80 / 0.5
    }

    @Test
    fun aBoxClippedToZeroAreaByFrameBoundsIsDropped() {
        // Fully outside the frame to the left: after clamping to [0, frameWidth], right <= left.
        val out = output(anchor(cx = -100f, cy = 100f, w = 20f, h = 20f, classId = personId, score = 0.9f))
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun nonMaximumSuppressionKeepsTheHigherConfidenceOfTwoHeavilyOverlappingSameClassBoxes() {
        val out = output(
            anchor(cx = 100f, cy = 100f, w = 40f, h = 40f, classId = personId, score = 0.9f),
            anchor(cx = 105f, cy = 105f, w = 40f, h = 40f, classId = personId, score = 0.5f)
        )
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertEquals(1, detections.size)
        assertEquals(0.9f, detections.single().confidence, 0.0001f)
    }

    @Test
    fun nonMaximumSuppressionKeepsTwoDisjointSameClassBoxes() {
        val out = output(
            anchor(cx = 50f, cy = 50f, w = 20f, h = 20f, classId = carId, score = 0.9f),
            anchor(cx = 500f, cy = 500f, w = 20f, h = 20f, classId = carId, score = 0.8f)
        )
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertEquals(2, detections.size)
    }

    @Test
    fun nonMaximumSuppressionNeverCompetesAcrossDifferentClasses() {
        // Same box, same confidence, but different classes: both should survive NMS.
        val out = output(
            anchor(cx = 100f, cy = 100f, w = 40f, h = 40f, classId = personId, score = 0.9f),
            anchor(cx = 100f, cy = 100f, w = 40f, h = 40f, classId = carId, score = 0.9f)
        )
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertEquals(setOf("person", "car"), detections.map { it.label }.toSet())
    }

    @Test
    fun intersectionOverUnionOfIdenticalBoxesIsOne() {
        val box = Detection("person", 0.9f, x = 10f, y = 10f, w = 20f, h = 20f, timestampMs = 0L)
        assertEquals(1f, YoloDecoder.intersectionOverUnion(box, box), 0.0001f)
    }

    @Test
    fun intersectionOverUnionOfDisjointBoxesIsZero() {
        val a = Detection("person", 0.9f, x = 0f, y = 0f, w = 10f, h = 10f, timestampMs = 0L)
        val b = Detection("person", 0.9f, x = 100f, y = 100f, w = 10f, h = 10f, timestampMs = 0L)
        assertEquals(0f, YoloDecoder.intersectionOverUnion(a, b), 0.0001f)
    }

    @Test
    fun decodeUsesConfigLabelsOrderNotTheHardcodedStockCocoOrder() {
        // A fine-tuned export with only 2 classes, in a deliberately non-COCO order: row 4 is
        // "cone" (not a COCO class at all), row 5 is "person". This must resolve by the config's
        // own `labels` list, never by falling back to [CocoLabels].
        val customConfig = DetectorConfig(
            modelAsset = "custom.onnx",
            inputSize = 640,
            confidenceThreshold = 0.45f,
            nmsIouThreshold = 0.5f,
            classes = setOf("cone", "person"),
            labels = listOf("cone", "person")
        )
        val row = FloatArray(6) // 4 box coords + 2 class scores
        row[0] = 100f; row[1] = 100f; row[2] = 40f; row[3] = 60f
        row[5] = 0.9f // "person" is index 1 in this custom label order
        val out = Array(6) { r -> FloatArray(1) { row[r] } }
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = customConfig)
        assertEquals("person", detections.single().label)
    }

    @Test
    fun anOutputWithNoAnchorsDecodesToNoDetections() {
        val out = output()
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = config)
        assertTrue(detections.isEmpty())
    }

    @Test
    fun classMapCollapsesSeveralRawLabelsIntoOneDisplayCategory() {
        // DetectorConfig.DEFAULT's real shape: a stock-COCO-labels model whose "car"/"bus" raw
        // classes both collapse into one "transportation" display class via classMap.
        val mergedConfig = DetectorConfig(
            modelAsset = "stock.onnx",
            inputSize = 640,
            confidenceThreshold = 0.45f,
            nmsIouThreshold = 0.5f,
            classes = setOf("transportation"),
            labels = CocoLabels.names,
            classMap = mapOf("car" to "transportation", "bus" to "transportation")
        )
        val out = output(
            anchor(cx = 50f, cy = 50f, w = 20f, h = 20f, classId = carId, score = 0.9f),
            anchor(cx = 500f, cy = 500f, w = 20f, h = 20f, classId = CocoLabels.names.indexOf("bus"), score = 0.8f)
        )
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = mergedConfig)
        assertEquals(2, detections.size)
        assertTrue(detections.all { it.label == "transportation" })
    }

    @Test
    fun aRawLabelNotInClassMapPassesThroughUnchanged() {
        val mergedConfig = DetectorConfig(
            modelAsset = "stock.onnx",
            inputSize = 640,
            confidenceThreshold = 0.45f,
            nmsIouThreshold = 0.5f,
            classes = setOf("person", "transportation"),
            labels = CocoLabels.names,
            classMap = mapOf("car" to "transportation")
        )
        val out = output(anchor(cx = 50f, cy = 50f, w = 20f, h = 20f, classId = personId, score = 0.9f))
        val detections = YoloDecoder.decode(out, 640, 640, 0L, scale = 1f, padX = 0f, padY = 0f, config = mergedConfig)
        assertEquals("person", detections.single().label)
    }
}
