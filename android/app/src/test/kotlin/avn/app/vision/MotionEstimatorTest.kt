package avn.app.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEstimatorTest {
    @Test
    fun staysNotWalkingBeforeTheWindowFills() {
        val window = MotionWindow(windowSize = 5, threshold = 1.0)

        repeat(4) { assertFalse(window.addSample(50.0)) }
    }

    @Test
    fun aSteadyMagnitudeIsNotWalking() {
        val window = MotionWindow(windowSize = 10, threshold = 1.0)

        var walking = false
        repeat(10) { walking = window.addSample(9.8) }

        assertFalse(walking)
    }

    @Test
    fun anOscillatingMagnitudeIsWalking() {
        val window = MotionWindow(windowSize = 10, threshold = 1.0)

        var walking = false
        repeat(10) { i -> walking = window.addSample(if (i % 2 == 0) 8.0 else 14.0) }

        assertTrue(walking)
    }

    @Test
    fun fakeMotionEstimatorReportsWhateverItIsToldTo() {
        val fake = FakeMotionEstimator()

        assertFalse(fake.isWalking())
        fake.walking = true
        assertTrue(fake.isWalking())
    }
}
