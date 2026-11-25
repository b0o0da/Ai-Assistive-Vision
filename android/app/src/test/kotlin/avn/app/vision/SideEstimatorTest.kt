package avn.app.vision

import avn.contracts.Side
import org.junit.Assert.assertEquals
import org.junit.Test

/** AVN-43 (WS-06): [SideEstimator] against known box/frame sizes, including the third boundaries. */
class SideEstimatorTest {
    private val estimator = SideEstimator()

    private fun box(x: Float, w: Float) = Detection(
        label = "person",
        confidence = 0.9f,
        x = x,
        y = 0f,
        w = w,
        h = 100f,
        timestampMs = 0
    )

    @Test
    fun aBoxCenteredInTheLeftThirdIsLeft() {
        // frame 900px wide, centre at 150 -- well inside [0, 300)
        assertEquals(Side.LEFT, estimator.estimate(box(x = 100f, w = 100f), frameWidthPx = 900f))
    }

    @Test
    fun aBoxCenteredInTheMiddleThirdIsCenter() {
        // centre at 450 -- middle of [300, 600)
        assertEquals(Side.CENTER, estimator.estimate(box(x = 400f, w = 100f), frameWidthPx = 900f))
    }

    @Test
    fun aBoxCenteredInTheRightThirdIsRight() {
        // centre at 750 -- well inside [600, 900)
        assertEquals(Side.RIGHT, estimator.estimate(box(x = 700f, w = 100f), frameWidthPx = 900f))
    }

    @Test
    fun centerExactlyAtTheLeftBoundaryIsCenterNotLeft() {
        // centre == 300 (exactly one third of 900) -- the boundary belongs to the next third.
        assertEquals(Side.CENTER, estimator.estimate(box(x = 250f, w = 100f), frameWidthPx = 900f))
    }

    @Test
    fun centerExactlyAtTheRightBoundaryIsRightNotCenter() {
        // centre == 600 (exactly two thirds of 900).
        assertEquals(Side.RIGHT, estimator.estimate(box(x = 550f, w = 100f), frameWidthPx = 900f))
    }
}
