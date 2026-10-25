package avn.app.vision

import avn.contracts.DistanceBand
import avn.contracts.EventType
import avn.contracts.Side
import avn.contracts.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AVN-45 (WS-06): [TrackEventBuilder] -- type mapping, the `risk = 0` contract (see
 * [TrackEventBuilder]'s KDoc and `Event.risk`'s own comment), and the "emit only on change" rate
 * limit (ticket step 3 / "Done when": rate limited).
 *
 * Frame is 900x1000px throughout, matching [SideEstimatorTest]'s and [DistanceBanderTest]'s own
 * fixture scale so the geometry here is directly comparable to those two tests.
 */
class EventBuilderTest {
    private val frameWidthPx = 900f
    private val frameHeightPx = 1000f

    private fun track(id: Int, label: String, x: Float, y: Float, w: Float, h: Float, ts: Long = 0): Track {
        val box = Detection(label = label, confidence = 0.8f, x = x, y = y, w = w, h = h, timestampMs = ts)
        return Track(id = id, label = label, history = listOf(box), lastBox = box, age = 1, lostFrames = 0)
    }

    // Centre-third x (CENTER side): x=400, w=100 -> centre 450 of 900.
    private val centerX = 400f
    private val centerW = 100f

    // NEAR for person-tier thresholds (height>=0.45, bottom>=0.80 against frameHeightPx=1000).
    private val nearY = 350f
    private val nearH = 500f

    // FAR for person-tier thresholds (height<0.20, bottom<0.50).
    private val farY = 50f
    private val farH = 100f

    @Test
    fun aNewTrackAlwaysEmitsAnEvent() {
        val builder = TrackEventBuilder()
        val event = builder.build(track(1, "person", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)
        assertNotNull(event)
    }

    @Test
    fun theEmittedEventCarriesTheRequiredFieldsAndRiskZero() {
        val builder = TrackEventBuilder()
        val input = track(7, "person", centerX, nearY, centerW, nearH, ts = 12345L)
        val event = builder.build(input, frameWidthPx, frameHeightPx)!!

        assertTrue(event.id, event.id.matches(Regex("^evt_[A-Za-z0-9_-]{1,64}$")))
        assertEquals(Source.CAMERA, event.source)
        assertEquals("person", event.label)
        assertEquals(Side.CENTER, event.side)
        assertEquals(DistanceBand.NEAR, event.distanceBand)
        assertEquals(0.8, event.confidence, 0.0001)
        assertEquals(0, event.risk) // producers send 0; the engine computes the real value
        assertEquals(12345L, event.timestamp)
    }

    @Test
    fun personLabelMapsToPersonClose() {
        val builder = TrackEventBuilder()
        val event = builder.build(track(1, "person", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)!!
        assertEquals(EventType.PERSON_CLOSE, event.type)
    }

    @Test
    fun vehicleLabelMapsToVehicleApproaching() {
        val builder = TrackEventBuilder()
        val event = builder.build(track(1, "vehicle", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)!!
        assertEquals(EventType.VEHICLE_APPROACHING, event.type)
    }

    @Test
    fun anyOtherLabelMapsToObstacleAhead() {
        val builder = TrackEventBuilder()
        val chair = builder.build(track(1, "chair", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)!!
        val bench = builder.build(track(2, "bench", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)!!
        assertEquals(EventType.OBSTACLE_AHEAD, chair.type)
        assertEquals(EventType.OBSTACLE_AHEAD, bench.type)
    }

    @Test
    fun aSecondCallWithNoChangeReturnsNull() {
        val builder = TrackEventBuilder()
        builder.build(track(1, "person", centerX, nearY, centerW, nearH, ts = 0), frameWidthPx, frameHeightPx)
        val later = track(1, "person", centerX, nearY, centerW, nearH, ts = 100)
        val second = builder.build(later, frameWidthPx, frameHeightPx)
        assertNull(second)
    }

    @Test
    fun bandEscalationReEmitsEvenWithTheSameSide() {
        val builder = TrackEventBuilder()
        builder.build(track(1, "person", centerX, farY, centerW, farH, ts = 0), frameWidthPx, frameHeightPx)
        val later = track(1, "person", centerX, nearY, centerW, nearH, ts = 100)
        val second = builder.build(later, frameWidthPx, frameHeightPx)
        assertNotNull(second)
        assertEquals(DistanceBand.NEAR, second!!.distanceBand)
    }

    @Test
    fun bandDeEscalationAloneDoesNotReEmit() {
        val builder = TrackEventBuilder()
        builder.build(track(1, "person", centerX, nearY, centerW, nearH, ts = 0), frameWidthPx, frameHeightPx)
        val later = track(1, "person", centerX, farY, centerW, farH, ts = 100)
        val second = builder.build(later, frameWidthPx, frameHeightPx)
        assertNull(second)
    }

    @Test
    fun sideChangeReEmitsEvenWithAnUnchangedBand() {
        val builder = TrackEventBuilder()
        // left third: x=100, w=100 -> centre 150 of 900
        builder.build(track(1, "person", 100f, nearY, 100f, nearH, ts = 0), frameWidthPx, frameHeightPx)
        // right third: x=700, w=100 -> centre 750 of 900, same band (near)
        val later = track(1, "person", 700f, nearY, 100f, nearH, ts = 100)
        val second = builder.build(later, frameWidthPx, frameHeightPx)
        assertNotNull(second)
        assertEquals(Side.RIGHT, second!!.side)
    }

    @Test
    fun differentTrackIdsAreTrackedIndependently() {
        val builder = TrackEventBuilder()
        val first = builder.build(track(1, "person", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)
        val second = builder.build(track(2, "person", centerX, nearY, centerW, nearH), frameWidthPx, frameHeightPx)
        assertNotNull("a different track id is a new track and should emit too", second)
        assertNotNull(first)
    }
}
