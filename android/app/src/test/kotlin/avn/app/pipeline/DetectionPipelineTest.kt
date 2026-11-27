package avn.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.LifecycleOwner
import avn.app.camera.Frame
import avn.app.camera.FrameSource
import avn.app.core.logging.JsonLinesLogger
import avn.app.vision.Detection
import avn.app.vision.Detector
import avn.app.vision.EventBuilder
import avn.app.vision.Track
import avn.app.vision.Tracker
import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WS05-09/AVN-10: "frames dropped not queued when busy; detections logged with timestamps". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionPipelineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val log = JsonLinesLogger(context)

    private fun frame(ts: Long, width: Int = 200, height: Int = 400, rotation: Int = 0) =
        Frame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), rotation, ts)

    private class StubFrameSource : FrameSource {
        override fun bind(lifecycleOwner: LifecycleOwner) = Unit

        override fun frames(): Flow<Frame> = emptyFlow()

        override suspend fun captureStill(): Frame? = null
    }

    private class StubTracker(private val tracks: List<Track>) : Tracker {
        override fun update(detections: List<Detection>, timestampMs: Long): List<Track> = tracks
    }

    private class StubEventBuilder(private val event: Event?) : EventBuilder {
        var lastFrameWidth: Float? = null
        var lastFrameHeight: Float? = null

        override fun build(track: Track, frameWidthPx: Float, frameHeightPx: Float): Event? {
            lastFrameWidth = frameWidthPx
            lastFrameHeight = frameHeightPx
            return event
        }
    }

    private class RecordingEngineInput : EngineInput {
        val events = mutableListOf<Event>()

        override fun submit(event: Event) {
            events.add(event)
        }

        override fun submit(candidate: SpeechCandidate) = Unit
    }

    private fun sampleTrack(): Track {
        val detection = Detection("person", 0.9f, 10f, 10f, 20f, 20f, 100L)
        return Track(
            id = 1,
            label = "person",
            history = listOf(detection),
            lastBox = detection,
            age = 1,
            lostFrames = 0
        )
    }

    private fun sampleEvent() = Event(
        id = "evt_1",
        source = Source.CAMERA,
        type = EventType.PERSON_CLOSE,
        label = "person",
        side = Side.LEFT,
        distanceBand = DistanceBand.NEAR,
        confidence = 0.9,
        risk = 0,
        timestamp = 100L
    )

    @Test
    fun aFrameArrivingWhileBusyIsDroppedNotQueued() = runBlocking {
        val enteredDetector = CompletableDeferred<Unit>()
        val releaseDetector = CompletableDeferred<Unit>()
        var detectCalls = 0
        val detector = object : Detector {
            override suspend fun detect(frame: Frame): List<Detection> {
                detectCalls++
                enteredDetector.complete(Unit)
                releaseDetector.await()
                return emptyList()
            }
        }
        val pipeline = DetectionPipeline(
            StubFrameSource(),
            detector,
            StubTracker(emptyList()),
            StubEventBuilder(null),
            RecordingEngineInput(),
            log
        )

        val firstFrameJob = launch { pipeline.processFrame(frame(1L)) }
        enteredDetector.await()

        // The pipeline is still inside detect() for frame 1 -- this one must be dropped, not queued.
        pipeline.processFrame(frame(2L))

        assertEquals(1, detectCalls)
        releaseDetector.complete(Unit)
        firstFrameJob.join()
    }

    @Test
    fun aProcessedFrameBuildsEventsFromTracksAndSubmitsThemWithTheUprightFrameSize() = runBlocking {
        val detector = object : Detector {
            override suspend fun detect(frame: Frame): List<Detection> = listOf(sampleTrack().lastBox)
        }
        val eventBuilder = StubEventBuilder(sampleEvent())
        val engineInput = RecordingEngineInput()
        val pipeline = DetectionPipeline(
            StubFrameSource(),
            detector,
            StubTracker(listOf(sampleTrack())),
            eventBuilder,
            engineInput,
            log
        )

        pipeline.processFrame(frame(100L, width = 200, height = 400, rotation = 0))

        assertEquals(listOf(sampleEvent()), engineInput.events)
        assertEquals(200f, eventBuilder.lastFrameWidth)
        assertEquals(400f, eventBuilder.lastFrameHeight)
    }

    @Test
    fun aNinetyDegreeRotationSwapsTheUprightFrameSize() = runBlocking {
        val eventBuilder = StubEventBuilder(null)
        val pipeline = DetectionPipeline(
            StubFrameSource(),
            object : Detector {
                override suspend fun detect(frame: Frame): List<Detection> = emptyList()
            },
            StubTracker(listOf(sampleTrack())),
            eventBuilder,
            RecordingEngineInput(),
            log
        )

        pipeline.processFrame(frame(100L, width = 200, height = 400, rotation = 90))

        assertEquals(400f, eventBuilder.lastFrameWidth)
        assertEquals(200f, eventBuilder.lastFrameHeight)
    }

    @Test
    fun aFrameWithNoBitmapIsSkippedWithoutCallingTheDetector() = runBlocking {
        var detectCalls = 0
        val detector = object : Detector {
            override suspend fun detect(frame: Frame): List<Detection> {
                detectCalls++
                return emptyList()
            }
        }
        val pipeline = DetectionPipeline(
            StubFrameSource(),
            detector,
            StubTracker(emptyList()),
            StubEventBuilder(null),
            RecordingEngineInput(),
            log
        )

        pipeline.processFrame(Frame(bitmap = null, rotationDegrees = 0, timestampMs = 1L))

        assertEquals(0, detectCalls)
    }

    @Test
    fun aDetectorFailureIsCaughtAndReleasesTheBusyLockForTheNextFrame() = runBlocking {
        var calls = 0
        val detector = object : Detector {
            override suspend fun detect(frame: Frame): List<Detection> {
                calls++
                if (calls == 1) error("boom")
                return emptyList()
            }
        }
        val pipeline = DetectionPipeline(
            StubFrameSource(),
            detector,
            StubTracker(emptyList()),
            StubEventBuilder(null),
            RecordingEngineInput(),
            log
        )

        pipeline.processFrame(frame(1L))
        pipeline.processFrame(frame(2L))

        assertTrue("expected the second frame to run after the first one's failure", calls == 2)
    }
}
