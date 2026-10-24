package eg.assistive.core.engine

import eg.assistive.core.model.DistanceBand
import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskModelTest {
    private val cfg = EngineConfig()
    private fun ev(type: String, band: DistanceBand = DistanceBand.UNKNOWN, side: Side = Side.UNKNOWN, conf: Double? = null, risk: Priority? = null) =
        Event("e", "cv", type, "", side, band, conf, risk)

    @Test fun scoreFormulaIsExact() {
        // vehicle 0.85 + near 0.15 + center 0.05 = 1.05 -> clamp 1.0, * (0.5 + 0.5 * 0.9) = 0.95
        assertEquals(0.95, RiskModel.score(ev("vehicle_approaching", DistanceBand.NEAR, Side.CENTER, 0.9)), 1e-9)
        // obstacle 0.55 - far 0.10 = 0.45, * (0.5 + 0.5 * 0.8) = 0.405
        assertEquals(0.405, RiskModel.score(ev("obstacle_ahead", DistanceBand.FAR, Side.LEFT, 0.8)), 1e-9)
    }

    @Test fun thresholdsMapScoreToLevel() {
        fun level(t: String, b: DistanceBand, c: Double?) = RiskModel.assess(ev(t, b, Side.CENTER, c), cfg).level
        assertEquals(Priority.CRITICAL, level("vehicle_approaching", DistanceBand.MEDIUM, 0.91))
        assertEquals(Priority.WARNING, level("vehicle_approaching", DistanceBand.FAR, 0.91))
        assertEquals(Priority.WARNING, level("obstacle_ahead", DistanceBand.NEAR, 0.91))
        assertEquals(Priority.INFO, level("text_detected", DistanceBand.NEAR, 0.91))
        assertEquals(Priority.INFO, level("scene_description", DistanceBand.FAR, 0.83))
    }

    @Test fun vehicleBoundaryAtConfidenceZeroPointSixNear() {
        assertEquals(Priority.CRITICAL, RiskModel.assess(ev("vehicle_approaching", DistanceBand.NEAR, Side.UNKNOWN, 0.6), cfg).level)
        assertEquals(Priority.WARNING, RiskModel.assess(ev("vehicle_approaching", DistanceBand.NEAR, Side.UNKNOWN, 0.59), cfg).level)
    }

    @Test fun missingFieldsNeverLowerTheScore() {
        assertTrue(RiskModel.score(ev("vehicle_approaching")) >= cfg.criticalThreshold)
        assertEquals(1.0, RiskModel.score(ev("sos", conf = 0.01)), 1e-9)
    }

    @Test fun nearerAndMoreCentralScoresHigher() {
        val a = RiskModel.score(ev("obstacle_ahead", DistanceBand.NEAR, Side.CENTER, 0.9))
        val b = RiskModel.score(ev("obstacle_ahead", DistanceBand.FAR, Side.LEFT, 0.9))
        assertTrue(a > b)
    }

    @Test fun hintSetsAFloorOnly() {
        assertEquals(Priority.CRITICAL, RiskModel.assess(ev("text_detected", risk = Priority.CRITICAL), cfg).level)
        assertEquals(Priority.WARNING, RiskModel.assess(ev("text_detected", risk = Priority.WARNING), cfg).level)
        assertEquals(Priority.CRITICAL, RiskModel.assess(ev("sos", risk = Priority.INFO), cfg).level)
    }

    @Test fun thresholdsAreConfigurable() {
        val strict = EngineConfig(criticalThreshold = 0.99)
        assertEquals(Priority.WARNING, RiskModel.assess(ev("vehicle_approaching", DistanceBand.NEAR, Side.CENTER, 0.9), strict).level)
    }

    /** The table in docs/risk-levels.md. If this test changes, update the doc. */
    @Test fun docExamplesMatchTheModel() {
        fun s(t: String, b: DistanceBand, side: Side, c: Double) = RiskModel.score(ev(t, b, side, c))
        assertEquals(0.950, s("vehicle_approaching", DistanceBand.NEAR, Side.CENTER, 0.90), 1e-9)
        assertEquals(0.907, s("vehicle_approaching", DistanceBand.MEDIUM, Side.CENTER, 0.91), 1e-9)
        assertEquals(0.764, s("vehicle_approaching", DistanceBand.FAR, Side.CENTER, 0.91), 1e-9)
        assertEquals(0.800, s("vehicle_approaching", DistanceBand.NEAR, Side.CENTER, 0.60), 1e-9)
        assertEquals(0.795, s("vehicle_approaching", DistanceBand.NEAR, Side.CENTER, 0.59), 1e-9)
        assertEquals(0.716, s("obstacle_ahead", DistanceBand.NEAR, Side.CENTER, 0.91), 1e-9)
        assertEquals(0.405, s("obstacle_ahead", DistanceBand.FAR, Side.LEFT, 0.80), 1e-9)
        assertEquals(0.334, s("text_detected", DistanceBand.NEAR, Side.CENTER, 0.91), 1e-9)
    }
}
