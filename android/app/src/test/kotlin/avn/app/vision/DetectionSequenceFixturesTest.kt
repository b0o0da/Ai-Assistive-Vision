package avn.app.vision

import android.content.Context
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Notion test name: fixture_replay_smoke (WS05-03). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionSequenceFixturesTest {
    private val dir = File(System.getProperty("avn.repoRoot"), "tests/fixtures/detections")
    private val context: Context = RuntimeEnvironment.getApplication()
    private val frame = Frame(bitmap = null, rotationDegrees = 0, timestampMs = 0)

    private val names: List<String> = Json.parseToJsonElement(File(dir, "index.json").readText())
        .jsonObject.getValue("sequences").jsonArray.map { it.jsonPrimitive.content }

    private fun load(name: String) = DetectionSequences.parse(File(dir, name).readText())

    @Test
    fun indexListsTheSixWs0503Sequences() {
        assertEquals(
            setOf("chair_approach", "person_close", "car_from_right", "crowded", "empty", "night"),
            names.map { it.removeSuffix(".json") }.toSet()
        )
    }

    @Test
    fun fakeDetectorReplaysEverySequenceFrameByFrame() = runBlocking {
        names.forEach { name ->
            val sequence = load(name)
            val detector = FakeDetector(JsonLinesLogger(context))
            detector.load(sequence)
            sequence.frames.forEach { expected ->
                assertEquals("$name @${expected.timestampMs}", expected.detections, detector.detect(frame))
            }
            assertTrue("$name: detector must be empty after the last frame", detector.detect(frame).isEmpty())
        }
    }

    @Test
    fun expectedBandAndSideMatchTheRealEstimators() {
        val bander = DistanceBander()
        val sides = SideEstimator()
        names.forEach { name ->
            val sequence = load(name)
            val actual = sequence.frames.last().detections.map { d ->
                ExpectedObject(
                    d.label,
                    bander.estimate(d, sequence.frameHeight.toFloat()),
                    sides.estimate(d, sequence.frameWidth.toFloat())
                )
            }
            assertEquals(name, sequence.expected.sortedBy { it.toString() }, actual.sortedBy { it.toString() })
        }
    }

    @Test
    fun approachSequencesGrowAndNeverGetFarther() {
        val bander = DistanceBander()
        listOf("chair_approach.json", "person_close.json", "car_from_right.json").forEach { name ->
            val sequence = load(name)
            val heights = sequence.frames.map { it.detections.single().h }
            assertTrue("$name: box must grow", heights.zipWithNext().all { (a, b) -> b > a })
            val height = sequence.frameHeight.toFloat()
            val first = bander.estimate(sequence.frames.first().detections.single(), height)
            val last = bander.estimate(sequence.frames.last().detections.single(), height)
            assertTrue("$name: $first -> $last must not get farther", last.ordinal <= first.ordinal)
        }
    }

    @Test
    fun trackerRunsOnEverySequence() {
        names.forEach { name ->
            val tracker = ByteTrackTracker()
            val outputs = load(name).frames.map { tracker.update(it.detections, it.timestampMs) }
            if (name == "empty.json") assertTrue(outputs.all { it.isEmpty() })
            if (name == "crowded.json") assertEquals(6, outputs.last().size)
        }
    }
}
