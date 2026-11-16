package avn.app.vision

import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Event
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
 * the velocity extrapolation and approach detection in [IouTracker] do not need to re-derive
 * motion from `lastBox` alone. `approaching` is [IouTracker]'s own verdict from that history and
 * the live [avn.app.vision.MotionEstimator] signal -- see [ApproachDetector].
 */
data class Track(
    val id: Int,
    val label: String,
    val history: List<Detection>,
    val lastBox: Detection,
    val age: Int,
    val lostFrames: Int,
    val approaching: Boolean = false
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

/** Result of one explicit camera-to-text request; no frame or text is persisted by this workflow. */
sealed interface OcrCapture {
    data class Found(val result: OcrResult) : OcrCapture
    data object Busy : OcrCapture
    data object CameraUnavailable : OcrCapture
    data object NoText : OcrCapture
    data object Failed : OcrCapture
}

/**
 * architecture.md WS-06 / AVN-45: "APIs / interfaces: ... EventBuilder.build(track): Event?".
 * The real signature also takes the analysed frame's pixel size -- [Detection] boxes are in that
 * frame's pixel coordinates (see [Detection]'s own doc comment) and [Frame] wraps a [Bitmap] whose
 * size is not a fixed constant (CameraX target resolution, letterboxing), so [SideEstimator] and
 * [DistanceBander] (both already take frame size per call, not as configuration) need it passed in
 * here too, the same deviation from architecture.md's one-line summary already taken by [Tracker]
 * (whose real implementation also carries constructor config architecture.md's one-liner elides).
 *
 * Returns null when there is nothing new worth telling the engine about -- see [TrackEventBuilder].
 */
interface EventBuilder {
    fun build(track: Track, frameWidthPx: Float, frameHeightPx: Float): Event?
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

@Singleton
class FakeEventBuilder @Inject constructor(private val log: JsonLinesLogger) : EventBuilder {
    override fun build(track: Track, frameWidthPx: Float, frameHeightPx: Float): Event? {
        log.log("event_builder", "fake_build", fields = mapOf("track" to track.id))
        return null
    }
}
