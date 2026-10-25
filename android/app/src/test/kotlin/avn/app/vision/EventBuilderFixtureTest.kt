package avn.app.vision

import avn.contracts.Event
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AVN-45 (WS-06) ticket step 4: "Validate emitted events against contracts/schemas/event.schema.json."
 *
 * There is no JVM JSON-Schema validator in the Gradle catalog (checked `gradle/libs.versions.toml`;
 * android/CLAUDE.md says not to add a dependency outside it without asking), but the repo already
 * has one: `scripts/validate_contracts.py` validates every fixture listed in
 * `contracts/fixtures/index.json` against the schemas in `contracts/schemas/` with Python's
 * `jsonschema` library. So instead of hand-rolling a second validator, this ticket's three
 * `builder_*.json` fixtures (added to that index) are real schema-validated proof -- `python3
 * scripts/validate_contracts.py` reports them `ok` alongside the other 57 (see the AVN-45 fixture
 * commit).
 *
 * What is missing from that alone is proof the fixtures actually match [TrackEventBuilder]'s real
 * output, not just hand-typed numbers that happen to satisfy the schema. This test closes that gap:
 * it rebuilds each fixture's exact scenario through the real [TrackEventBuilder] and
 * [SideEstimator]/[DistanceBander] pipeline and asserts the result equals the fixture decoded via
 * [Event]'s own `kotlinx.serialization` model (the same "deserializes into the Kotlin models"
 * contract test rule android/CLAUDE.md states for every fixture).
 */
class EventBuilderFixtureTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/events")

    private fun decode(name: String): Event = Json.decodeFromString(Event.serializer(), File(fixtures, name).readText())

    private fun track(
        id: Int,
        label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        confidence: Float,
        ts: Long
    ): Track {
        val box = Detection(label = label, confidence = confidence, x = x, y = y, w = w, h = h, timestampMs = ts)
        return Track(id = id, label = label, history = listOf(box), lastBox = box, age = 1, lostFrames = 0)
    }

    @Test
    fun personCloseFixtureMatchesTheRealBuilderOutput() {
        val expected = decode("builder_person_close_left_medium.json")
        val builder = TrackEventBuilder()
        val actual = builder.build(
            track(
                id = 42,
                label = "person",
                x = 100f,
                y = 550f,
                w = 120f,
                h = 650f,
                confidence = 0.875f,
                ts = 1760000001000L
            ),
            frameWidthPx = 1080f,
            frameHeightPx = 1920f
        )
        assertEquals(expected, actual)
    }

    @Test
    fun vehicleApproachingFixtureMatchesTheRealBuilderOutput() {
        val expected = decode("builder_vehicle_approaching_right_near.json")
        val builder = TrackEventBuilder()
        val actual = builder.build(
            track(
                id = 7,
                label = "vehicle",
                x = 600f,
                y = 1300f,
                w = 400f,
                h = 550f,
                confidence = 0.8125f,
                ts = 1760000002000L
            ),
            frameWidthPx = 1080f,
            frameHeightPx = 1920f
        )
        assertEquals(expected, actual)
    }

    @Test
    fun obstacleAheadFixtureMatchesTheRealBuilderOutput() {
        val expected = decode("builder_obstacle_ahead_center_medium.json")
        val builder = TrackEventBuilder()
        val actual = builder.build(
            track(
                id = 15,
                label = "chair",
                x = 450f,
                y = 800f,
                w = 250f,
                h = 300f,
                confidence = 0.75f,
                ts = 1760000003000L
            ),
            frameWidthPx = 1080f,
            frameHeightPx = 1920f
        )
        assertEquals(expected, actual)
    }
}
