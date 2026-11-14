package avn.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IouTracker]'s ByteTrack-style association (high-confidence first, then low-confidence
 * recovery-only), track loss after [TrackerConfig.maxLostFrames], and [ApproachDetector]'s
 * sustained-area-growth rule, against the real thresholds loaded from `config/tracker.json`
 * (`highConfidence=0.45, lowConfidence=0.10, matchIou=0.30, maxLostFrames=8, historySize=6,
 * approachMinObservations=5, approachMinSpanMs=350, approachGrowthRatio=1.35`) -- a change to
 * that file that breaks these expectations is a signal the config drifted, not a test bug.
 */
class IouTrackerTest {
    private fun box(label: String, x: Float, y: Float, w: Float, h: Float, confidence: Float, ts: Long) =
        Detection(label = label, confidence = confidence, x = x, y = y, w = w, h = h, timestampMs = ts)

    @Test
    fun aNewHighConfidenceDetectionCreatesANewTrack() {
        val tracker = IouTracker()
        val result = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L)
        assertEquals(1, result.size)
        assertEquals(1, result.single().age)
        assertEquals("person", result.single().label)
    }

    @Test
    fun twoSimultaneousDetectionsGetDistinctIds() {
        val tracker = IouTracker()
        val result = tracker.update(
            listOf(
                box("person", 0f, 0f, 50f, 50f, 0.9f, 0L),
                box("person", 500f, 500f, 50f, 50f, 0.9f, 0L)
            ),
            0L
        )
        assertEquals(2, result.size)
        assertNotEquals(result[0].id, result[1].id)
    }

    @Test
    fun aHighlyOverlappingDetectionOnTheNextFrameContinuesTheSameTrack() {
        val tracker = IouTracker()
        val firstId = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L).single().id
        val second = tracker.update(listOf(box("person", 5f, 5f, 100f, 100f, 0.9f, 33L)), 33L).single()
        assertEquals(firstId, second.id)
        assertEquals(2, second.age)
    }

    @Test
    fun aDetectionWithTooLittleOverlapStartsANewTrackInstead() {
        val tracker = IouTracker()
        val firstId = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L).single().id
        // Shifted almost entirely away: IoU well under the 0.30 match threshold.
        val second = tracker.update(listOf(box("person", 300f, 300f, 100f, 100f, 0.9f, 33L)), 33L).single()
        assertNotEquals(firstId, second.id)
        assertEquals(1, second.age)
    }

    @Test
    fun aLowConfidenceDetectionNeverCreatesANewTrack() {
        val tracker = IouTracker()
        // Between lowConfidence (0.10) and highConfidence (0.45): valid, but recovery-only.
        val result = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.30f, 0L)), 0L)
        assertTrue("a low-confidence-only detection must not start a track", result.isEmpty())
    }

    @Test
    fun aLowConfidenceDetectionCanRecoverAnExistingTrack() {
        val tracker = IouTracker()
        val firstId = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L).single().id
        val second = tracker.update(listOf(box("person", 5f, 5f, 100f, 100f, 0.30f, 33L)), 33L)
        assertEquals(1, second.size)
        assertEquals(firstId, second.single().id)
        assertEquals(2, second.single().age)
    }

    @Test
    fun aDetectionBelowTheLowConfidenceFloorIsFilteredOutEntirely() {
        val tracker = IouTracker()
        val result = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.05f, 0L)), 0L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun aTrackIsDroppedAfterExceedingMaxLostFramesAndANewOverlappingDetectionStartsAFreshTrack() {
        val tracker = IouTracker()
        val firstId = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L).single().id
        // maxLostFrames = 8: 9 consecutive empty frames push lostFrames to 9 (> 8), evicting the track.
        repeat(9) { tracker.update(emptyList(), it.toLong()) }
        val revived = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 999L)), 999L).single()
        assertNotEquals("the old track must not be reused once it was evicted", firstId, revived.id)
        assertEquals(1, revived.age)
    }

    @Test
    fun aTrackSurvivesFewerThanMaxLostFramesAndCanStillBeMatchedAfterward() {
        val tracker = IouTracker()
        val firstId = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 0L)), 0L).single().id
        // lostFrames increments at the start of every update() call, including the recovery call
        // itself: 7 empty calls bring it to 7, the 8th (recovery) call bumps it to 8 (not > 8 yet,
        // so still alive when the removal check runs) and then the match resets it to 0.
        repeat(7) { tracker.update(emptyList(), it.toLong()) }
        val recovered = tracker.update(listOf(box("person", 0f, 0f, 100f, 100f, 0.9f, 999L)), 999L).single()
        assertEquals(firstId, recovered.id)
    }

    /** Grows box area ~21%/frame, centered in place, across [count] frames 100ms apart. */
    private fun growingVehicleFrames(label: String, count: Int): List<Detection> {
        var size = 100f
        return (0 until count).map { index ->
            val detection = box(label, x = 50f - size / 2f, y = 50f - size / 2f, w = size, h = size, confidence = 0.9f, ts = index * 100L)
            size *= 1.1f
            detection
        }
    }

    @Test
    fun aVehicleWithSustainedAreaGrowthIsReportedAsApproaching() {
        val tracker = IouTracker()
        var last: Track? = null
        for (frame in growingVehicleFrames("car", 6)) {
            last = tracker.update(listOf(frame), frame.timestampMs).single()
        }
        assertTrue("expected the car track to be flagged approaching", last!!.approaching)
    }

    @Test
    fun aNonVehicleLabelIsNeverReportedAsApproachingEvenWithTheSameAreaGrowth() {
        val tracker = IouTracker()
        var last: Track? = null
        for (frame in growingVehicleFrames("person", 6)) {
            last = tracker.update(listOf(frame), frame.timestampMs).single()
        }
        assertTrue("person is not in ApproachDetector's vehicle label set", !last!!.approaching)
    }

    @Test
    fun aVehicleWithShrinkingAreaIsNotReportedAsApproaching() {
        val tracker = IouTracker()
        var size = 160f
        var last: Track? = null
        repeat(6) { index ->
            val frame = box("car", x = 50f - size / 2f, y = 50f - size / 2f, w = size, h = size, confidence = 0.9f, ts = index * 100L)
            last = tracker.update(listOf(frame), frame.timestampMs).single()
            size *= 0.9f
        }
        assertTrue("a shrinking (receding) box must not be flagged approaching", !last!!.approaching)
    }

    @Test
    fun aVehicleThatOnlyJustAppearedIsNotYetReportedAsApproaching() {
        val tracker = IouTracker()
        // approachMinObservations = 5: with only 3 frames, approach must stay false regardless of growth.
        var last: Track? = null
        for (frame in growingVehicleFrames("car", 3)) {
            last = tracker.update(listOf(frame), frame.timestampMs).single()
        }
        assertTrue(!last!!.approaching)
    }
}
