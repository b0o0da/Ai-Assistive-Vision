package avn.app.vision

import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.contracts.OcrResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

data class Track(val id: Int, val label: String, val lastBox: Detection, val age: Int, val lostFrames: Int)

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

/**
 * Real [OcrEngine]: ML Kit Text Recognition (Latin), per android/CLAUDE.md's stack list. [read] is
 * deliberately blocking inside a bounded timeout -- a timeout, no text and a transient ML Kit error
 * all collapse to "nothing read" (fail soft) rather than a crash, and the block runs on
 * [Dispatchers.Default], never the caller's thread, so a slow/garbled frame can't stall the camera
 * pipeline (architecture rule 6, "no work on the main thread").
 *
 * Returns the single [OcrResult] the frozen seam asks for; per-block boxes/positions aren't part of
 * that contract, so whole-image recognized text is concatenated rather than split into blocks -- a
 * block-aware reading UI, if one is wanted later, is vision-pipeline integration work, not this seam.
 *
 * [recognizer] is lazy, not a constructor-time field: `TextRecognition.getClient(...)` reaches
 * into `MlKitContext`, which only exists once ML Kit's own ContentProvider has initialised the
 * app -- real on a device, but not under a plain Hilt/Robolectric test, where this class still
 * gets constructed as the unused "real" candidate in RealSeamsModule even when the fake is
 * selected. Deferring to first [read] keeps that construction side-effect-free, consistent with
 * "models lazy-load... off the main thread" (android/CLAUDE.md rule 6).
 */
@Singleton
class MlKitOcrEngine @Inject constructor(private val log: JsonLinesLogger) : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(frame: Frame): OcrResult = withContext(Dispatchers.Default) {
        val bitmap = frame.bitmap
        if (bitmap == null) {
            log.log("ocr", "read_no_frame")
            return@withContext OcrResult(text = "", confidence = 0.0, language = "en", source = "camera")
        }
        val text: Text? = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, frame.rotationDegrees)), 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.log("ocr", "read_failed", fields = mapOf("error" to e.javaClass.simpleName))
            null
        }
        val read = text?.text?.trim().orEmpty()
        log.log("ocr", "read", fields = mapOf("found" to read.isNotEmpty()))
        OcrResult(
            text = read,
            confidence = if (read.isNotEmpty()) 1.0 else 0.0,
            language = "en",
            source = "camera"
        )
    }
}
