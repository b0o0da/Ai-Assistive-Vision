package avn.app.vision

import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.contracts.OcrResult
import javax.inject.Inject
import javax.inject.Singleton

/** Box in pixels of the analysed frame. */
data class Detection(
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val timestampMs: Long
)

/**
 * A stable identity across frames (architecture.md WS-06). `history` is every detection the
 * track has been matched to so far (oldest first, capped by the tracker that builds it), kept so
 * a future velocity/approach estimator does not need to re-derive motion from `lastBox` alone.
 */
data class Track(
    val id: Int,
    val label: String,
    val history: List<Detection>,
    val lastBox: Detection,
    val age: Int,
    val lostFrames: Int
)

interface Detector {
    suspend fun detect(frame: Frame): List<Detection>
}

interface Tracker {
    fun update(detections: List<Detection>, timestampMs: Long): List<Track>
}

interface OcrEngine {
    suspend fun read(frame: Frame): OcrResult
}

@Singleton
class FakeDetector @Inject constructor(private val log: JsonLinesLogger) : Detector {
    override suspend fun detect(frame: Frame): List<Detection> {
        log.log("detector", "fake_detect")
        return emptyList()
    }
}

@Singleton
class FakeTracker @Inject constructor(private val log: JsonLinesLogger) : Tracker {
    override fun update(detections: List<Detection>, timestampMs: Long): List<Track> {
        log.log("tracker", "fake_update", fields = mapOf("detections" to detections.size))
        return emptyList()
    }
}

@Singleton
class FakeOcrEngine @Inject constructor(private val log: JsonLinesLogger) : OcrEngine {
    override suspend fun read(frame: Frame): OcrResult {
        log.log("ocr", "fake_read")
        return OcrResult(text = "", confidence = 0.0, language = "en", source = "fake")
    }
}
