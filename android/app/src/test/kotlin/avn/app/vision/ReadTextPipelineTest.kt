package avn.app.vision

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import avn.app.camera.Frame
import avn.app.camera.FrameSource
import avn.app.core.AppPriorityEngine
import avn.app.core.logging.JsonLinesLogger
import avn.app.voice.FakeSpeaker
import avn.contracts.EventType
import avn.contracts.Level
import avn.engine.Decision
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WS07-07 (AVN-134): frame -> OCR -> engine -> speaker, replayed from the OCR fixtures in
 * `tests/fixtures/ocr/` with the real engine binding ([AppPriorityEngine]) and [FakeSpeaker].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadTextPipelineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val repo = File(System.getProperty("avn.repoRoot"))
    private val log = JsonLinesLogger(context)
    private val speaker = FakeSpeaker(log)
    private val engine = AppPriorityEngine(speaker, log)
    private val ocr = FakeOcrEngine(log)
    private val summary = OcrSpeechSummary(OcrSpeechConfig.DEFAULT)

    /** Replays still frames (no pixels needed: the OCR side is replayed too); null = no camera. */
    private class ReplayFrameSource(private val stills: ArrayDeque<Frame?>) : FrameSource {
        var captures = 0

        override fun bind(lifecycleOwner: LifecycleOwner) = Unit

        override fun frames(): Flow<Frame> = emptyFlow()

        override suspend fun captureStill(): Frame? {
            captures++
            return stills.removeFirstOrNull()
        }
    }

    private fun frame(t: Long) = Frame(bitmap = null, rotationDegrees = 0, timestampMs = t)

    private fun fixture(name: String) = OcrFixtures.parse(File(repo, "tests/fixtures/ocr/$name").readText())

    private fun pipeline(frames: FrameSource, engineOcr: OcrEngine = ocr) =
        ReadTextPipeline(frames, engineOcr, engine, log, summary)

    @Test
    fun test_read_text_pipeline_replay() = runBlocking {
        ocr.enqueue(fixture("sign_pharmacy.json"))
        val outcome = pipeline(ReplayFrameSource(ArrayDeque(listOf(frame(1))))).readOnce()

        assertTrue(outcome is ReadTextPipeline.Outcome.Read)
        val heard = speaker.spoken.value.single()
        assertEquals("PHARMACY. Open until 10 PM.", heard.text)
        assertEquals(Level.INFO, heard.level)
        val record = engine.decisions.value.single()
        assertEquals(EventType.TEXT_RESULT, record.type)
        assertEquals(Decision.SPOKEN, record.decision)
        assertEquals("bypass (asked)", record.threshold)
    }

    @Test
    fun test_no_text_message() = runBlocking {
        ocr.enqueue(fixture("blank_wall.json"))
        val outcome = pipeline(ReplayFrameSource(ArrayDeque(listOf(frame(1))))).readOnce()

        assertEquals(ReadTextPipeline.Outcome.NoText(NoTextReason.NO_TEXT, "I can't see any text."), outcome)
        assertEquals("I can't see any text.", speaker.spoken.value.single().text)
    }

    @Test
    fun noCameraIsSpokenInsteadOfSilence() = runBlocking {
        val frames = ReplayFrameSource(ArrayDeque(listOf(null)))
        val outcome = pipeline(frames).readOnce()

        assertEquals(NoTextReason.EMPTY_FRAME, (outcome as ReadTextPipeline.Outcome.NoText).reason)
        assertEquals(NoTextReason.EMPTY_FRAME.spoken, speaker.spoken.value.single().text)
    }

    @Test
    fun ocrErrorIsSpokenAndTheNextRequestStillWorks() = runBlocking {
        val failing = object : OcrEngine {
            var calls = 0

            override suspend fun read(frame: Frame): OcrReading {
                calls++
                if (calls == 1) error("recognizer crashed")
                return fixture("sign_pharmacy.json")
            }
        }
        val p = pipeline(ReplayFrameSource(ArrayDeque(listOf(frame(1), frame(2)))), failing)

        val first = p.readOnce()
        assertEquals(NoTextReason.ENGINE_ERROR, (first as ReadTextPipeline.Outcome.NoText).reason)
        assertTrue(p.readOnce() is ReadTextPipeline.Outcome.Read)
        assertEquals(
            listOf(NoTextReason.ENGINE_ERROR.spoken, "PHARMACY. Open until 10 PM."),
            speaker.spoken.value.map { it.text }
        )
    }

    @Test
    fun aSecondRequestWhileReadingIsBusyAndSpeaksNothingExtra() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = object : OcrEngine {
            override suspend fun read(frame: Frame): OcrReading {
                gate.await()
                return fixture("sign_pharmacy.json")
            }
        }
        val p = pipeline(ReplayFrameSource(ArrayDeque(listOf(frame(1), frame(2)))), slow)

        val first = async { p.readOnce() }
        yield()
        assertEquals(ReadTextPipeline.Outcome.Busy, p.readOnce())
        gate.complete(Unit)
        assertTrue(first.await() is ReadTextPipeline.Outcome.Read)
        assertEquals(1, speaker.spoken.value.size)
    }

    @Test
    fun recognisedTextNeverReachesTheLog() = runBlocking {
        ocr.enqueue(fixture("sign_pharmacy.json"))
        pipeline(ReplayFrameSource(ArrayDeque(listOf(frame(1))))).readOnce()

        val logFile = File(context.filesDir, "logs/avn.jsonl")
        for (attempt in 0 until 50) {
            if (logFile.exists() && logFile.readText().contains("\"read_text\"")) break
            Thread.sleep(20)
        }
        val written = logFile.readText()
        assertTrue("read_text line written", written.contains("\"event\":\"read_text\""))
        assertFalse(written.contains("PHARMACY"))
        assertFalse(written.contains("Open until"))
    }
}
