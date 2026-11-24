package avn.contracts

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** AVN-35 (WS02-04, TD-008) and AVN-88 (WS12-02, TD-011): Transcript fixtures and schema agree with the Kotlin model, including the optional is_final; ActionPlan keeps its frozen fields. */
class TranscriptContractTest {
    private val root = File(System.getProperty("avn.repoRoot"))
    private val dir = File(root, "contracts/fixtures/transcripts")
    private val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun schemaDef(name: String) =
        Json.parseToJsonElement(File(root, "contracts/schemas/android.schema.json").readText())
            .jsonObject.getValue("\$defs").jsonObject.getValue(name).jsonObject

    @Test
    fun tenTranscriptFixturesRoundTrip() {
        assertEquals(10, files.size)
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
        // Read the fields from the serializer, not from an encoded value: Json omits defaults.
        val descriptor = Transcript.serializer().descriptor
        val indices = 0 until descriptor.elementsCount
        val kotlinKeys = indices.map { descriptor.getElementName(it) }.toSet()
        val kotlinRequired = indices.filterNot { descriptor.isElementOptional(it) }
            .map { descriptor.getElementName(it) }.toSet()
        assertEquals(schemaKeys, kotlinKeys)
        assertEquals(required, kotlinRequired)
        // TD-011: is_final is the only optional field.
        assertEquals(setOf("is_final"), schemaKeys - required)
    }

    @Test
    fun isFinalIsOptionalAndAbsentMeansTrue() {
        // TD-011: the 8 pre-1.3.0 fixtures carry no is_final; they must still decode as final.
        val bare = Json.decodeFromString(
            Transcript.serializer(),
            """{"text":"hi","confidence":0.9,"language":"en"}"""
        )
        assertTrue(bare.isFinal)
        val legacy = files.filter { !it.readText().contains("is_final") }
        assertEquals(8, legacy.size)
        legacy.forEach { file ->
            assertTrue(file.name, Json.decodeFromString(Transcript.serializer(), file.readText()).isFinal)
        }
    }

    @Test
    fun finalAndPartialFixturesSetIsFinalExplicitly() {
        val finalT = Json.decodeFromString(Transcript.serializer(), File(dir, "final.json").readText())
        val partialT = Json.decodeFromString(Transcript.serializer(), File(dir, "partial.json").readText())
        assertTrue(finalT.isFinal)
        assertFalse(partialT.isFinal)
        assertTrue(partialT.text.isNotEmpty())
        listOf("final.json", "partial.json").forEach { name ->
            val obj = Json.parseToJsonElement(File(dir, name).readText()).jsonObject
            assertTrue(name, obj.containsKey("is_final"))
        }
    }

    @Test
    fun partialFlagSurvivesEncodeAndDecode() {
        // Json omits defaults, so is_final shows up on the wire only when it is false.
        val partialT = Transcript("what is", 0.5, "en", isFinal = false)
        val wire = Json.encodeToString(Transcript.serializer(), partialT)
        assertEquals("false", Json.parseToJsonElement(wire).jsonObject.getValue("is_final").jsonPrimitive.content)
        assertEquals(partialT, Json.decodeFromString(Transcript.serializer(), wire))
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
