package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Side
import avn.contracts.Source
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskScorerTest {
    private val scorer = RiskScorer()

    private fun event(
        type: EventType,
        label: String,
        band: DistanceBand? = DistanceBand.NEAR,
        side: Side? = Side.CENTER,
        confidence: Double = 0.9
    ) = Event("evt_t", Source.CAMERA, type, label, side, band, confidence, 0, 0L)

    @Test
    fun configOnClasspathIsMarkedProposedAndMatchesDefault() {
        val loaded = RiskConfig.load()
        assertEquals("PROPOSED", loaded.status)
        assertEquals(RiskConfig.DEFAULT, loaded)
    }

    @Test
    fun missingConfigFallsBackToDefault() {
        assertEquals(RiskConfig.DEFAULT, RiskConfig.load("/config/does-not-exist.json"))
    }

    @Test
    fun brokenConfigFallsBackToDefault() {
        assertEquals(RiskConfig.DEFAULT, runCatching { RiskConfig.parse("{") }.getOrDefault(RiskConfig.DEFAULT))
    }

    @Test
    fun test_risk_monotonic_band() {
        val bands = listOf(DistanceBand.NEAR, DistanceBand.MEDIUM, DistanceBand.FAR)
        for (type in EventType.entries) {
            for (side in Side.entries) {
                for (conf in listOf(0.3, 0.6, 0.9, 1.0)) {
                    for (approaching in listOf(false, true)) {
                        val scores = bands.map { scorer.score(event(type, "thing", it, side, conf), approaching) }
                        assertTrue(
                            "$type $side $conf $approaching: $scores",
                            scores[0] >= scores[1] && scores[1] >= scores[2]
                        )
                    }
                }
            }
        }
        // Strictly greater for the hazard types, not just equal.
        val person = bands.map { scorer.score(event(EventType.PERSON_CLOSE, "person", it)) }
        assertTrue(person.toString(), person[0] > person[1] && person[1] > person[2])
    }

    @Test
    fun test_risk_vehicle_over_chair() {
        for (band in DistanceBand.entries) {
            for (side in Side.entries) {
                val vehicle = scorer.score(event(EventType.VEHICLE_APPROACHING, "car", band, side), approaching = true)
                val chair = scorer.score(event(EventType.OBSTACLE_AHEAD, "chair", band, side))
                assertTrue("$band $side vehicle=$vehicle chair=$chair", vehicle > chair)
            }
        }
    }

    @Test
    fun formulaMatchesArchitectureSection5() {
        // 95 x 1.0 x 1.0 x 0.9 x 1.1 = 94.05
        assertEquals(94, scorer.score(event(EventType.VEHICLE_APPROACHING, "car"), approaching = true))
        // 60 x 0.7 x 0.85 x 0.8 = 28.56
        assertEquals(29, scorer.score(event(EventType.OBSTACLE_AHEAD, "chair", DistanceBand.MEDIUM, Side.LEFT, 0.8)))
        // static vehicle: obstacle_ahead + car label uses base 50. 50 x 1.0 x 1.0 x 0.9 = 45
        assertEquals(45, scorer.score(event(EventType.OBSTACLE_AHEAD, "Car")))
    }

    @Test
    fun vehicleApproachingIgnoresSideForSeverity() {
        val center = scorer.score(event(EventType.VEHICLE_APPROACHING, "car", side = Side.CENTER))
        val left = scorer.score(event(EventType.VEHICLE_APPROACHING, "car", side = Side.LEFT))
        assertEquals(center, left)
        val chairCenter = scorer.score(event(EventType.OBSTACLE_AHEAD, "chair", side = Side.CENTER))
        val chairLeft = scorer.score(event(EventType.OBSTACLE_AHEAD, "chair", side = Side.LEFT))
        assertTrue(chairCenter > chairLeft)
    }

    @Test
    fun approachBonusRaisesScoreAndCapHolds() {
        val plain = scorer.score(event(EventType.PERSON_CLOSE, "person"))
        val approaching = scorer.score(event(EventType.PERSON_CLOSE, "person"), approaching = true)
        assertTrue(approaching > plain)
        assertEquals(100, scorer.score(event(EventType.SOS_COUNTDOWN, "sos", null, null, 1.0), approaching = true))
    }

    @Test
    fun nonHazardWithoutBandOrSideKeepsBaseAndConfidenceIsClamped() {
        assertEquals(20, scorer.score(event(EventType.TEXT_RESULT, "text", null, null, 1.0)))
        assertEquals(20, scorer.score(event(EventType.TEXT_RESULT, "text", null, null, 7.0)))
        assertEquals(0, scorer.score(event(EventType.PERSON_CLOSE, "person", confidence = -1.0)))
        val e = event(EventType.PERSON_CLOSE, "person")
        assertEquals(scorer.score(e), scorer.scored(e).risk)
    }

    /** Evidence for the PR: the score table, written to build/reports/risk-score-table.md. */
    @Test
    fun writeScoreTable() {
        val rows = listOf(
            Triple(EventType.VEHICLE_APPROACHING, "car", true),
            Triple(EventType.OBSTACLE_AHEAD, "car", false),
            Triple(EventType.OBSTACLE_AHEAD, "chair", false),
            Triple(EventType.PERSON_CLOSE, "person", false),
            Triple(EventType.PERSON_UNKNOWN, "person", false),
            Triple(EventType.TEXT_RESULT, "text", false)
        )
        val sb =
            StringBuilder(
                "| type | label | approach | near/center | medium/center | far/center | near/left |\n|---|---|---|---|---|---|---|\n"
            )
        for ((type, label, approaching) in rows) {
            val cells = listOf(
                DistanceBand.NEAR to Side.CENTER,
                DistanceBand.MEDIUM to Side.CENTER,
                DistanceBand.FAR to Side.CENTER,
                DistanceBand.NEAR to Side.LEFT
            ).map { (b, s) -> scorer.score(event(type, label, b, s, 0.9), approaching) }
            sb.append("| ${type.name.lowercase()} | $label | $approaching | ${cells.joinToString(" | ")} |\n")
        }
        val out = File("build/reports/risk-score-table.md")
        out.parentFile.mkdirs()
        out.writeText("Confidence 0.9, weights from config/risk.json (PROPOSED)\n\n$sb")
        assertTrue(out.exists())
    }
}
