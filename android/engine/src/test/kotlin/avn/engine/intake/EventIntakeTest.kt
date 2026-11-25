package avn.engine.intake

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: test_event_intake_valid_invalid (WS16-02). */
class EventIntakeTest {
    private val root = File(System.getProperty("avn.repoRoot"))
    private val sample = File(root, "contracts/fixtures/events/obstacle_ahead_medium.json").readText()

    private fun rejected(text: String): String {
        val result = EventIntake.parse(text)
        assertTrue("expected rejection, got $result", result is IntakeResult.Rejected)
        return (result as IntakeResult.Rejected).reason
    }

    private fun sampleWith(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(sample).jsonObject.toMutableMap()
        change(map)
        return JsonObject(map).toString()
    }

    @Test
    fun acceptsEveryContractEventFixture() {
        val files = File(root, "contracts/fixtures/events").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no event fixtures", files.size >= 9)
        files.forEach { file ->
            val result = EventIntake.parse(file.readText())
            assertTrue("${file.name}: $result", result is IntakeResult.Accepted)
        }
    }

    @Test
    fun rejectsEveryInvalidEventFixture() {
        val files = File(root, "tests/fixtures/contract_invalid").listFiles { f ->
            f.name.startsWith("event.")
        }.orEmpty()
        assertEquals(5, files.size)
        files.forEach { file -> rejected(file.readText()) }
    }

    @Test
    fun rejectsEachMissingRequiredFieldByName() {
        EventIntake.REQUIRED_FIELDS.forEach { field ->
            val reason = rejected(sampleWith { it.remove(field) })
            assertTrue("reason should name $field: $reason", reason.contains(field))
        }
    }

    @Test
    fun acceptsNullSideAndBand() {
        val nulls = sampleWith {
            it["side"] = JsonNull
            it["distance_band"] = JsonNull
        }
        assertTrue(EventIntake.parse(nulls) is IntakeResult.Accepted)
    }

    @Test
    fun rejectsBadValues() {
        rejected(sampleWith { it["confidence"] = JsonPrimitive(1.5) })
        rejected(sampleWith { it["risk"] = JsonPrimitive(101) })
        rejected(sampleWith { it["timestamp"] = JsonPrimitive(-1) })
        rejected(sampleWith { it["id"] = JsonPrimitive("event_1") })
        rejected(sampleWith { it["label"] = JsonPrimitive("x".repeat(65)) })
        rejected(sampleWith { it["confidence"] = JsonPrimitive("high") })
    }

    @Test
    fun neverThrowsOnGarbage() {
        listOf("", "not json", "[]", "42", "{").forEach { rejected(it) }
    }

    @Test
    fun rejectionReasonDoesNotEchoContent() {
        val reason = rejected(sampleWith { it["label"] = JsonPrimitive("secret-" + "x".repeat(70)) })
        assertFalse(reason.contains("secret"))
    }
}
