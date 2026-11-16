package avn.contracts

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** AVN-35 (WS02-04, TD-008): Transcript fixtures and schema agree with the Kotlin model; ActionPlan keeps its frozen fields. */
class TranscriptContractTest {
    private val root = File(System.getProperty("avn.repoRoot"))
    private val dir = File(root, "contracts/fixtures/transcripts")
    private val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun schemaDef(name: String) =
        Json.parseToJsonElement(File(root, "contracts/schemas/android.schema.json").readText())
            .jsonObject.getValue("\$defs").jsonObject.getValue(name).jsonObject

    @Test
    fun eightTranscriptFixturesRoundTrip() {
        assertEquals(8, files.size)
        files.forEach { file ->
            val transcript = Json.decodeFromString(Transcript.serializer(), file.readText())
            assertEquals(
                file.name,
                transcript,
                Json.decodeFromString(Transcript.serializer(), Json.encodeToString(Transcript.serializer(), transcript))
            )
            assertTrue(file.name, transcript.confidence in 0.0..1.0)
            assertTrue(file.name, transcript.language in setOf("en", "ar"))
        }
    }

    @Test
    fun emptyTranscriptIsIncludedAndHasZeroConfidence() {
        val empty = Json.decodeFromString(Transcript.serializer(), File(dir, "empty.json").readText())
        assertEquals("", empty.text)
        assertEquals(0.0, empty.confidence, 0.0)
    }

    @Test
    fun kotlinFieldsMatchTheSchema() {
        val def = schemaDef("Transcript")
        val schemaKeys = def.getValue("properties").jsonObject.keys
        val required = def.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val kotlinKeys = Json.parseToJsonElement(
            Json.encodeToString(Transcript.serializer(), Transcript("", 0.0, "en"))
        ).jsonObject.keys
        assertEquals(schemaKeys, kotlinKeys)
        assertEquals(schemaKeys, required)
    }

    @Test
    fun transcriptRejectsUnknownOrMissingFields() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                Transcript.serializer(),
                """{"text":"hi","confidence":0.9,"language":"en","audio":"x"}"""
            )
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(Transcript.serializer(), """{"text":"hi","language":"en"}""")
        }
    }

    @Test
    fun actionPlanKeepsItsFrozenFields() {
        // TD-008: Notion action/params/speak_text map onto these; renaming them would be a 2.0.0 change.
        val keys = schemaDef("ActionPlan").getValue("properties").jsonObject.keys
        assertEquals(setOf("steps", "requires_confirmation", "spoken_ack", "clarification"), keys)
        val step = schemaDef("PlanStepItem").getValue("properties").jsonObject.keys
        assertEquals(setOf("step", "arg"), step)
    }
}
