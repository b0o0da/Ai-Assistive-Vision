package avn.app.vision

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AVN-40 (WS-06): runs [ByteTrackTracker] over the detection fixtures from `tests/fixtures/detections`
 * (there is no WS05-03 fixture set yet -- this ticket's own "Deliver" is the tracker, so these
 * three are authored here as the fixtures it needs, per the repo's "fixtures are deliverables"
 * rule).
 *
 * `Detection` is a plain (non-`@Serializable`) data class -- see the class doc on [Detection] for
 * why -- so fixtures are parsed by hand through [kotlinx.serialization.json.JsonElement] instead
 * of `Json.decodeFromString`.
 */
class ByteTrackTrackerTest {
    private fun loadFixture(name: String): List<Pair<Long, List<Detection>>> {
        val file = File(System.getProperty("avn.repoRoot"), "tests/fixtures/detections/$name")
        assertTrue("fixture not found: ${file.absolutePath}", file.exists())
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["frames"]!!.jsonArray.map { frameElement ->
            val frame = frameElement.jsonObject
            val timestampMs = frame["timestampMs"]!!.jsonPrimitive.long
            val detections = frame["detections"]!!.jsonArray.map { detectionElement ->
                val fields = detectionElement.jsonObject
                Detection(
                    label = fields["label"]!!.jsonPrimitive.contentOrNull!!,
                    confidence = fields["confidence"]!!.jsonPrimitive.float,
                    x = fields["x"]!!.jsonPrimitive.float,
                    y = fields["y"]!!.jsonPrimitive.float,
                    w = fields["w"]!!.jsonPrimitive.float,
                    h = fields["h"]!!.jsonPrimitive.float,
                    timestampMs = timestampMs
                )
            }
            timestampMs to detections
        }
    }

    private fun runFixture(tracker: ByteTrackTracker, name: String): List<List<Track>> =
        loadFixture(name).map { (timestampMs, detections) -> tracker.update(detections, timestampMs) }

    // ---- Done when: tracks form across frames of 3 fixtures ----

    @Test
    fun chairApproachFormsATrackOnTheFirstFrameAndKeepsItThroughout() {
        val outputs = runFixture(ByteTrackTracker(), "chair_approach.json")
        assertTrue("expected every frame to report a track", outputs.all { it.size == 1 })
        assertEquals(15, outputs.size)
    }

    @Test
    fun crossingFormsExactlyTwoTracksTotal() {
        val tracker = ByteTrackTracker()
        val outputs = runFixture(tracker, "crossing.json")
        val everyIdSeen = outputs.flatten().map { it.id }.toSet()
        // Two people in, no more than two ids total -- i.e. the tracker never fragments one
        // person into extra spurious tracks around the crossing.
        assertTrue("expected at most 2 distinct track ids, got $everyIdSeen", everyIdSeen.size <= 2)
        assertTrue("expected both people tracked on every frame", outputs.all { it.size == 2 })
    }

    @Test
    fun occlusionFormsATrackThatSurvivesTheGap() {
        val tracker = ByteTrackTracker()
        val outputs = runFixture(tracker, "occlusion.json")
        val framesWithATrack = outputs.filter { it.isNotEmpty() }
        assertTrue("expected the person to be tracked before and after the occlusion", framesWithATrack.size >= 9)
    }

    // ---- Done when: no ID swaps in chair_approach ----

    @Test
    fun chairApproachNeverSwapsId() {
        val outputs = runFixture(ByteTrackTracker(), "chair_approach.json")
        val ids = outputs.map { frame -> frame.single().id }
        assertTrue("chair_approach must keep one id throughout, saw $ids", ids.distinct().size == 1)
    }

    @Test
    fun chairApproachAgeIncreasesEveryFrameWithNoLostFrames() {
        val outputs = runFixture(ByteTrackTracker(), "chair_approach.json")
        val track = outputs.last().single()
        assertEquals(15, track.age)
        assertEquals(0, track.lostFrames)
        assertEquals(15, track.history.size)
    }

    // ---- Crossing: the velocity prediction is what keeps each person's own id ----

    @Test
    fun crossingKeepsEachPersonsOwnIdAcrossTheCrossingPoint() {
        val tracker = ByteTrackTracker()
        val outputs = runFixture(tracker, "crossing.json")
        // Track starting on the left (first frame, smaller x) must still be the leftmost-origin
        // id after the crossing; i.e. no swap, even though the two boxes overlap heavily
        // around the midpoint (frames 5-6 where they pass each other).
        val firstFrame = outputs.first().sortedBy { it.lastBox.x }
        val idThatStartedLeft = firstFrame[0].id
        val idThatStartedRight = firstFrame[1].id
        val lastFrame = outputs.last().associateBy { it.id }
        assertTrue(
            "id that started on the left should end on the right",
            lastFrame[idThatStartedLeft]!!.lastBox.x > 600f,
        )
        assertTrue(
            "id that started on the right should end on the left",
            lastFrame[idThatStartedRight]!!.lastBox.x < 300f,
        )
    }

    // ---- Occlusion: same id before and after the gap, not a new one ----

    @Test
    fun occlusionKeepsTheSameIdAfterReappearing() {
        val tracker = ByteTrackTracker()
        val outputs = runFixture(tracker, "occlusion.json")
        val idBeforeOcclusion = outputs[3].single().id // last frame before the 3-frame gap (indices 4,5,6 are empty)
        val idAfterOcclusion = outputs[7].single().id // first frame after the gap
        assertEquals(
            "occlusion should not create a new id once the person reappears",
            idBeforeOcclusion,
            idAfterOcclusion,
        )
    }

    // ---- Interrupted tracks are dropped, not requeued, once they exceed the lost-frame budget ----

    @Test
    fun aTrackLostForLongerThanTheBudgetIsDroppedForGood() {
        val tracker = ByteTrackTracker(maxLostFrames = 2)
        tracker.update(listOf(Detection("person", 0.9f, 100f, 100f, 50f, 50f, 0)), 0)
        // 3 frames with no detections at all -- longer than maxLostFrames=2.
        tracker.update(emptyList(), 100)
        tracker.update(emptyList(), 200)
        val afterDrop = tracker.update(emptyList(), 300)
        assertTrue(afterDrop.isEmpty())
        // The same box reappearing now must be a *new* id, not a revived old one.
        val revived = tracker.update(listOf(Detection("person", 0.9f, 100f, 100f, 50f, 50f, 400)), 400)
        assertEquals(2, revived.single().id)
    }

    // ---- Done when: runs in under 2 ms per frame ----

    @Test
    fun staysWellUnderTheTwoMillisecondPerFrameBudget() {
        val tracker = ByteTrackTracker()
        val random = java.util.Random(42)
        val labels = listOf("person", "vehicle", "chair")
        var timestampMs = 0L
        val frames = (0 until 300).map {
            timestampMs += 33
            (0 until 8).map { i ->
                Detection(
                    label = labels[i % labels.size],
                    confidence = 0.9f,
                    x = 50f + i * 90f + random.nextFloat() * 10f,
                    y = 400f + random.nextFloat() * 20f,
                    w = 100f,
                    h = 200f,
                    timestampMs = timestampMs
                )
            }
        }
        // Warm up the JIT before measuring, same as any other micro-benchmark.
        frames.take(20).forEach { tracker.update(it, it.first().timestampMs) }
        val start = System.nanoTime()
        frames.forEach { tracker.update(it, it.first().timestampMs) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        val perFrameMs = elapsedMs / frames.size
        assertTrue("expected well under 2ms/frame, measured ${perFrameMs}ms/frame", perFrameMs < 2.0)
    }
}
