package avn.app.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS06-02 (Notion test name: track_id_stability_test), on the WS05-03 detection fixtures. */
class TrackIdStabilityTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "tests/fixtures/detections")
    private val config = TrackerConfig.load()

    private fun sequence(name: String) = DetectionSequences.parse(File(fixtures, "$name.json").readText())

    @Test
    fun configHasBothLimits() {
        assertEquals(8, config.maxLostFrames)
        assertEquals(1_500L, config.maxLostMs)
    }

    @Test
    fun track_id_stability_test() {
        val frames = sequence("person_close").frames
        assertTrue("fixture needs enough frames", frames.size >= 12)
        val tracker = IouTracker(config)
        val ids = mutableSetOf<Int>()
        val report = StringBuilder("frame,ts,detections_in,track_ids\n")
        frames.forEachIndexed { i, frame ->
            // Frames 4..8 are "missed": the detector returns nothing for five frames in a row.
            val input = if (i in 4..8) emptyList() else frame.detections
            val tracks = tracker.update(input, frame.timestampMs)
            tracks.forEach { ids += it.id }
            report.append("$i,${frame.timestampMs},${input.size},${tracks.map { it.id }}\n")
        }
        File("build/reports/track-id-stability.csv").apply { parentFile.mkdirs() }.writeText(report.toString())
        assertEquals("the person keeps one ID through 5 missed frames", setOf(1), ids)
    }

    @Test
    fun expiresAfterTheConfiguredTimeoutEvenWithFewMissedFrames() {
        val tracker = IouTracker(config)
        val box = Detection("person", 0.9f, 100f, 100f, 100f, 200f, 0L)
        val first = tracker.update(listOf(box), 0L).single().id
        // Only two empty frames, but 1.6 s apart (low frame rate while standing still).
        tracker.update(emptyList(), 800L)
        tracker.update(emptyList(), 1_600L)
        val after = tracker.update(listOf(box), 1_700L).single().id
        assertNotEquals("expired after maxLostMs", first, after)
    }

    @Test
    fun aNewObjectGetsANewIdAndIdsAreNeverReused() {
        val tracker = IouTracker(config)
        val a = tracker.update(listOf(Detection("person", 0.9f, 0f, 0f, 100f, 200f, 0L)), 0L).single().id
        val b = tracker.update(
            listOf(
                Detection("person", 0.9f, 0f, 0f, 100f, 200f, 100L),
                Detection("chair", 0.9f, 600f, 600f, 150f, 150f, 100L)
            ),
            100L
        ).map { it.id }
        assertTrue(a in b)
        val chair = b.single { it != a }
        assertNotEquals(a, chair)
        // Both expire, then the same boxes come back: brand-new IDs, never the old ones.
        tracker.update(emptyList(), 5_000L)
        val again = tracker.update(listOf(Detection("person", 0.9f, 0f, 0f, 100f, 200f, 5_100L)), 5_100L).single().id
        assertFalse(again in setOf(a, chair))
    }

    @Test
    fun lifecycleRules() {
        val l = TrackLifecycle(maxLostFrames = 8, maxLostMs = 1_500)
        assertEquals(1, l.newId())
        assertEquals(2, l.newId())
        assertFalse(l.isExpired(lostFrames = 5, lastSeenMs = 0, nowMs = 500))
        assertTrue(l.isExpired(lostFrames = 9, lastSeenMs = 0, nowMs = 500))
        assertTrue(l.isExpired(lostFrames = 2, lastSeenMs = 0, nowMs = 1_501))
    }
}
