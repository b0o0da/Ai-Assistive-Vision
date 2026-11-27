package avn.app.pipeline

import avn.app.camera.Frame
import avn.app.camera.FrameSource
import avn.app.core.logging.JsonLinesLogger
import avn.app.vision.Detector
import avn.app.vision.EventBuilder
import avn.app.vision.Tracker
import avn.engine.EngineInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex

/**
 * WS05-09/AVN-10: the first live camera-to-detector pipeline (needed for the G2 demo). Every
 * frame from [FrameSource.frames] goes through [Detector], [Tracker] and [EventBuilder] in turn;
 * every non-null [avn.contracts.Event] reaches [EngineInput.submit] -- the only path to speech
 * (android/CLAUDE.md rule 2, "the priority engine is the product"). A frame that arrives while the
 * previous one is still being processed is dropped, never queued (architecture rule 3; the task's
 * "frames dropped not queued when busy").
 */
@Singleton
class DetectionPipeline @Inject constructor(
    private val frameSource: FrameSource,
    private val detector: Detector,
    private val tracker: Tracker,
    private val eventBuilder: EventBuilder,
    private val engineInput: EngineInput,
    private val log: JsonLinesLogger
) {
    private val busy = Mutex()

    /** Starts the continuous loop on [scope]. A failure on one frame never kills the loop or
     * [avn.app.core.service.AssistantService] (rule 5) -- it is caught per frame, not per call. */
    fun start(scope: CoroutineScope) {
        frameSource.frames().onEach(::processFrame).launchIn(scope)
    }

    /** Internal (not private) so [DetectionPipelineTest] can drive it directly, deterministically,
     * instead of racing a live camera callback through [start]'s Flow. */
    internal suspend fun processFrame(frame: Frame) {
        if (!busy.tryLock()) {
            log.log("pipeline", "frame_dropped_busy")
            return
        }
        try {
            val bitmap = frame.bitmap ?: return
            val detections = detector.detect(frame)
            val tracks = tracker.update(detections, frame.timestampMs)
            val (widthPx, heightPx) = uprightSize(bitmap.width, bitmap.height, frame.rotationDegrees)
            var eventsSubmitted = 0
            tracks.forEach { track ->
                eventBuilder.build(track, widthPx, heightPx)?.let { event ->
                    engineInput.submit(event)
                    eventsSubmitted++
                }
            }
            log.log(
                "pipeline",
                "frame_processed",
                fields = mapOf(
                    "frame_ts" to frame.timestampMs,
                    "detections" to detections.size,
                    "tracks" to tracks.size,
                    "events" to eventsSubmitted
                )
            )
        } catch (error: Exception) {
            log.log("pipeline", "frame_failed", fields = mapOf("error" to error.javaClass.simpleName))
        } finally {
            busy.unlock()
        }
    }

    /**
     * [avn.app.vision.Detection] boxes are in the frame's own pixel space *after* rotation
     * correction (see [avn.app.vision.YoloV8OnnxDetector]/[avn.app.vision.YoloDecoder]), which
     * swaps width/height for a 90/270 degree rotation -- [EventBuilder.build] needs that same
     * upright size, not the raw, pre-rotation bitmap's.
     */
    private fun uprightSize(bitmapWidth: Int, bitmapHeight: Int, rotationDegrees: Int): Pair<Float, Float> =
        if (rotationDegrees % 180 != 0) {
            bitmapHeight.toFloat() to bitmapWidth.toFloat()
        } else {
            bitmapWidth.toFloat() to bitmapHeight.toFloat()
        }
}
