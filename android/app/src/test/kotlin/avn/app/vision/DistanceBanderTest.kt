package avn.app.vision

import avn.contracts.DistanceBand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AVN-43 (WS-06): [DistanceBander] against known box/frame sizes for each configured class
 * (person, vehicle, chair) and the default fallback (an unlisted class), plus the "nearer wins"
 * tie-break between the height-ratio and bottom-edge-ratio signals described in
 * [DistanceBander]'s KDoc.
 *
 * Thresholds exercised here are the real ones loaded from `config/bands.json` (see
 * `BandThresholds.load()`'s default resource path), not hand-rolled ones, so a change to the
 * config file that breaks these expectations is a signal the config drifted, not a test bug.
 */
class DistanceBanderTest {
    private val bander = DistanceBander()

    private fun box(y: Float, h: Float, label: String = "person") = Detection(
        label = label,
        confidence = 0.9f,
        x = 0f,
        y = y,
        w = 100f,
        h = h,
        timestampMs = 0
    )

    // -- person (near 0.45/0.80, medium 0.20/0.50) --

    @Test
    fun personWithBothSignalsAgreeingNearIsNear() {
        // heightRatio = 500/1000 = 0.5 (>= 0.45), bottomRatio = (350+500)/1000 = 0.85 (>= 0.80)
        assertEquals(DistanceBand.NEAR, bander.estimate(box(y = 350f, h = 500f), frameHeightPx = 1000f))
    }

    @Test
    fun personWithBothSignalsAgreeingMediumIsMedium() {
        // heightRatio = 300/1000 = 0.3 (in [0.20, 0.45)), bottomRatio = 550/1000 = 0.55 (in [0.50, 0.80))
        assertEquals(DistanceBand.MEDIUM, bander.estimate(box(y = 250f, h = 300f), frameHeightPx = 1000f))
    }

    @Test
    fun personWithBothSignalsAgreeingFarIsFar() {
        // heightRatio = 100/1000 = 0.1 (< 0.20), bottomRatio = 150/1000 = 0.15 (< 0.50)
        assertEquals(DistanceBand.FAR, bander.estimate(box(y = 50f, h = 100f), frameHeightPx = 1000f))
    }

    // -- vehicle (near 0.30/0.70, medium 0.12/0.40) --

    @Test
    fun vehicleWithBothSignalsAgreeingNearIsNear() {
        // heightRatio = 400/1000 = 0.4 (>= 0.30), bottomRatio = 750/1000 = 0.75 (>= 0.70)
        assertEquals(
            DistanceBand.NEAR,
            bander.estimate(box(y = 350f, h = 400f, label = "vehicle"), frameHeightPx = 1000f)
        )
    }

    @Test
    fun vehicleWithBothSignalsAgreeingMediumIsMedium() {
        // heightRatio = 200/1000 = 0.2 (in [0.12, 0.30)), bottomRatio = 450/1000 = 0.45 (in [0.40, 0.70))
        assertEquals(
            DistanceBand.MEDIUM,
            bander.estimate(box(y = 250f, h = 200f, label = "vehicle"), frameHeightPx = 1000f)
        )
    }

    @Test
    fun vehicleWithBothSignalsAgreeingFarIsFar() {
        // heightRatio = 50/1000 = 0.05 (< 0.12), bottomRatio = 70/1000 = 0.07 (< 0.40)
        assertEquals(
            DistanceBand.FAR,
            bander.estimate(box(y = 20f, h = 50f, label = "vehicle"), frameHeightPx = 1000f)
        )
    }

    // -- chair (near 0.30/0.70, medium 0.12/0.40 -- same tier as vehicle) --

    @Test
    fun chairWithBothSignalsAgreeingNearIsNear() {
        // heightRatio = 350/1000 = 0.35 (>= 0.30), bottomRatio = 750/1000 = 0.75 (>= 0.70)
        assertEquals(
            DistanceBand.NEAR,
            bander.estimate(box(y = 400f, h = 350f, label = "chair"), frameHeightPx = 1000f)
        )
    }

    @Test
    fun chairWithBothSignalsAgreeingFarIsFar() {
        // heightRatio = 80/1000 = 0.08 (< 0.12), bottomRatio = 110/1000 = 0.11 (< 0.40)
        assertEquals(
            DistanceBand.FAR,
            bander.estimate(box(y = 30f, h = 80f, label = "chair"), frameHeightPx = 1000f)
        )
    }

    // -- default fallback (an unlisted class: near 0.35/0.75, medium 0.15/0.45) --

    @Test
    fun unlistedClassFallsBackToDefaultThresholds() {
        // "bench" is not in config/bands.json's "classes" map.
        // heightRatio = 400/1000 = 0.4 (>= 0.35), bottomRatio = 800/1000 = 0.8 (>= 0.75)
        assertEquals(
            DistanceBand.NEAR,
            bander.estimate(box(y = 400f, h = 400f, label = "bench"), frameHeightPx = 1000f)
        )
    }

    @Test
    fun unlistedClassFarUsesDefaultThresholdsToo() {
        // heightRatio = 100/1000 = 0.1 (< 0.15), bottomRatio = 150/1000 = 0.15 (< 0.45)
        assertEquals(
            DistanceBand.FAR,
            bander.estimate(box(y = 50f, h = 100f, label = "bench"), frameHeightPx = 1000f)
        )
    }

    // -- disagreement: "nearer wins" (see DistanceBander's KDoc) --

    @Test
    fun aTallBoxHighInFrameFromCameraTiltIsBiasedNear() {
        // A tall box (camera-tilt artifact) near the top of the frame: height alone says NEAR,
        // but the bottom edge (near the top, not the bottom of the frame) alone would only say
        // MEDIUM. The bias favours the nearer reading over under-warning.
        // heightRatio = 500/1000 = 0.5 (>= 0.45 -> NEAR), bottomRatio = (10+500)/1000 = 0.51 (in [0.50, 0.80) -> MEDIUM)
        assertEquals(DistanceBand.NEAR, bander.estimate(box(y = 10f, h = 500f), frameHeightPx = 1000f))
    }

    @Test
    fun aBoxClippedAtTheTopButLowInFrameIsBiasedNear() {
        // A box clipped at the top of the frame reads shorter than the real object: height alone
        // says FAR, but the bottom edge is low in frame (near real position), which alone says
        // NEAR. The bias favours the nearer reading.
        // heightRatio = 150/1000 = 0.15 (< 0.20 -> FAR), bottomRatio = (700+150)/1000 = 0.85 (>= 0.80 -> NEAR)
        assertEquals(DistanceBand.NEAR, bander.estimate(box(y = 700f, h = 150f), frameHeightPx = 1000f))
    }
}
