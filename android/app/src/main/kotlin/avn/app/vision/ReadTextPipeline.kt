package avn.app.vision

import avn.app.camera.Frame
import avn.app.camera.FrameSource
import avn.app.core.logging.JsonLinesLogger
import avn.engine.EngineInput
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * WS07-07 (AVN-134): the read-text path, frame -> OCR -> engine. One request captures one still,
 * runs OCR on the phone (works offline: nothing here touches the network), and submits exactly one
 * user-requested `text_result` candidate built by [OcrSpeechSummary]; the engine speaks it as Info.
 * Every failure is spoken too: no camera, no text, blurry, or an OCR error.
 *
 * Privacy: the frame is recycled when the request ends and is never stored; the recognised text is
 * returned to the caller (the Read text screen shows it) and handed to the engine, but only counts
 * and the no-text reason go to the log.
 */
@Singleton
class ReadTextPipeline internal constructor(
    private val frameSource: FrameSource,
    private val ocrEngine: OcrEngine,
    private val engine: EngineInput,
    private val log: JsonLinesLogger,
    private val summary: OcrSpeechSummary
) {
    @Inject constructor(frameSource: FrameSource, ocrEngine: OcrEngine, engine: EngineInput, log: JsonLinesLogger) :
        this(frameSource, ocrEngine, engine, log, OcrSpeechSummary())

    sealed interface Outcome {
        /** What the user heard (or will hear once the engine's queue reaches it). */
        val spoken: String?

        data class Read(val reading: OcrReading, override val spoken: String) : Outcome

        data class NoText(val reason: NoTextReason, override val spoken: String) : Outcome

        /** A request is already running; nothing new is spoken. */
        data object Busy : Outcome {
            override val spoken: String? = null
        }
    }

    private val mutex = Mutex()
    private val counter = AtomicLong()

    suspend fun readOnce(): Outcome {
        if (!mutex.tryLock()) return Outcome.Busy
        var frame: Frame? = null
        return try {
            frame = frameSource.captureStill()
            val reading = if (frame == null) {
                OcrReading(emptyList(), LANGUAGE, SOURCE_NONE, NoTextReason.EMPTY_FRAME)
            } else {
                ocrEngine.read(frame)
            }
            submit(reading)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            submit(OcrReading(emptyList(), LANGUAGE, SOURCE_NONE, NoTextReason.ENGINE_ERROR))
        } finally {
            frame?.bitmap?.recycle()
            mutex.unlock()
        }
    }

    private fun submit(reading: OcrReading): Outcome {
        val id = "ocr_${counter.incrementAndGet()}"
        val candidate = summary.candidate(reading, eventId = id)
        engine.submit(candidate)
        val reason = if (reading.isEmpty) reading.noText ?: NoTextReason.NO_TEXT else null
        log.log(
            "ocr",
            "read_text",
            fields = mapOf(
                "id" to id,
                "source" to reading.source,
                "blocks" to reading.blocks.size,
                "spoken_chars" to candidate.text.length,
                "no_text" to reason?.name?.lowercase()
            )
        )
        return if (reason != null) Outcome.NoText(reason, candidate.text) else Outcome.Read(reading, candidate.text)
    }

    private companion object {
        const val LANGUAGE = "en"
        const val SOURCE_NONE = "none"
    }
}
