package avn.app.vision

import avn.contracts.DistanceBand
import avn.contracts.Side
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AVN-43 (WS-06) "Done when": 90% match on labeled fixtures. Loads the 20 hand-labeled cases in
 * `tests/fixtures/bands/labeled_boxes.json` (fixtures are deliverables, see root CLAUDE.md) and
 * runs both [SideEstimator] and [DistanceBander] against each one, asserting at least 90% agree
 * with the expected band and the expected side independently.
 */
class LabeledBandFixturesTest {
    private val fixture = File(System.getProperty("avn.repoRoot"), "tests/fixtures/bands/labeled_boxes.json")

    private val sideEstimator = SideEstimator()
    private val distanceBander = DistanceBander()

    private data class Case(
        val label: String,
        val frameWidth: Float,
        val frameHeight: Float,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val expectedBand: DistanceBand,
        val expectedSide: Side
    )

    private fun loadCases(): List<Case> {
        val root = Json.parseToJsonElement(fixture.readText()).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val obj = element.jsonObject
            Case(
                label = obj.getValue("label").jsonPrimitive.content,
                frameWidth = obj.getValue("frameWidth").jsonPrimitive.int.toFloat(),
                frameHeight = obj.getValue("frameHeight").jsonPrimitive.int.toFloat(),
                x = obj.getValue("x").jsonPrimitive.int.toFloat(),
                y = obj.getValue("y").jsonPrimitive.int.toFloat(),
                w = obj.getValue("w").jsonPrimitive.int.toFloat(),
                h = obj.getValue("h").jsonPrimitive.int.toFloat(),
                expectedBand = when (obj.getValue("expectedBand").jsonPrimitive.content) {
                    "near" -> DistanceBand.NEAR
                    "medium" -> DistanceBand.MEDIUM
                    "far" -> DistanceBand.FAR
                    else -> error("unknown band in fixture: $obj")
                },
                expectedSide = when (obj.getValue("expectedSide").jsonPrimitive.content) {
                    "left" -> Side.LEFT
                    "center" -> Side.CENTER
                    "right" -> Side.RIGHT
                    else -> error("unknown side in fixture: $obj")
                }
            )
        }
    }

    private fun Case.toDetection() = Detection(
        label = label,
        confidence = 0.9f,
        x = x,
        y = y,
        w = w,
        h = h,
        timestampMs = 0
    )

    @Test
    fun atLeast90PercentOfLabeledFixturesMatchOnBand() {
        val cases = loadCases()
        assertTrue("no fixture cases found at $fixture", cases.isNotEmpty())

        val mismatches = cases.filter { case ->
            distanceBander.estimate(case.toDetection(), frameHeightPx = case.frameHeight) != case.expectedBand
        }
        val matchRate = (cases.size - mismatches.size).toDouble() / cases.size
        assertTrue(
            "band match rate $matchRate < 0.9; mismatches: $mismatches",
            matchRate >= 0.9
        )
    }

    @Test
    fun atLeast90PercentOfLabeledFixturesMatchOnSide() {
        val cases = loadCases()
        assertTrue("no fixture cases found at $fixture", cases.isNotEmpty())

        val mismatches = cases.filter { case ->
            sideEstimator.estimate(case.toDetection(), frameWidthPx = case.frameWidth) != case.expectedSide
        }
        val matchRate = (cases.size - mismatches.size).toDouble() / cases.size
        assertTrue(
            "side match rate $matchRate < 0.9; mismatches: $mismatches",
            matchRate >= 0.9
        )
    }
}
