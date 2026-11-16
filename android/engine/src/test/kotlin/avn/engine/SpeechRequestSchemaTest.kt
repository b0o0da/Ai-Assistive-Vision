package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechRequest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: test_speech_request_schema. */
class SpeechRequestSchemaTest {
    private val schemaFile = File(System.getProperty("avn.repoRoot"), "contracts/schemas/android.schema.json")

    private val request = SpeechRequest(
        utteranceId = "utt_1",
        text = "Car approaching on your left.",
        level = Level.CRITICAL,
        source = Source.CAMERA,
        interruptible = false,
        eventId = "evt_0001"
    )

    private fun event(side: Side?, band: DistanceBand?) = Event(
        id = "evt_x",
        source = Source.CAMERA,
        type = EventType.VEHICLE_APPROACHING,
        label = "Car",
        side = side,
        distanceBand = band,
        confidence = 0.9,
        risk = 0,
        timestamp = 0
    )

    @Test
    fun speechRequestJsonMatchesTheFrozenSchema() {
        val speechDef = Json.parseToJsonElement(schemaFile.readText())
            .jsonObject["\$defs"]!!.jsonObject["SpeechRequest"]!!.jsonObject
        val allowed = speechDef["properties"]!!.jsonObject.keys
        val required = speechDef["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

        val encoded = Json { encodeDefaults = true }.encodeToString(SpeechRequest.serializer(), request)
        val keys = Json.parseToJsonElement(encoded).jsonObject.keys

        assertEquals("Kotlin SpeechRequest fields differ from android.schema.json", allowed, keys)
        assertTrue("a required field is missing", keys.containsAll(required))
    }

    @Test
    fun speechRequestRoundTrips() {
        val text = Json.encodeToString(SpeechRequest.serializer(), request)
        assertEquals(request, Json.decodeFromString(SpeechRequest.serializer(), text))
    }

    @Test
    fun speechItemCarriesTheDedupKeyOfItsEvent() {
        val key = DedupKey.of(event(Side.LEFT, DistanceBand.NEAR))
        val item = SpeechItem(request, dedupKey = key)
        assertEquals("vehicle_approaching|car|left|near", item.dedupKey)
        assertNull("speech not tied to an event has no key", SpeechItem(request).dedupKey)
    }

    @Test
    fun ttlComesFromQueueConfigPerLevel() {
        val config = SpeechQueueConfig.load()
        assertNull("Critical never expires", config.expiryMsFor(Level.CRITICAL))
        assertTrue(config.expiryMsFor(Level.WARNING)!! > 0)
        assertTrue(config.expiryMsFor(Level.INFO)!! > config.expiryMsFor(Level.WARNING)!!)
    }

    @Test
    fun decisionAndReasonUseLogNames() {
        assertEquals("spoken", Decision.SPOKEN.wire)
        assertEquals(Decision.EXPIRED, decisionFromWire("expired"))
        assertNull(decisionFromWire("shouted"))
        assertEquals("queue_overflow", Reason.QUEUE_OVERFLOW.wire)
        assertEquals(Reason.COOLDOWN, Reason.fromWire("cooldown"))
    }

    @Test
    fun dedupKeyUsesTypeLabelSideAndBand() {
        assertEquals("vehicle_approaching|car|left|near", DedupKey.of(event(Side.LEFT, DistanceBand.NEAR)))
        assertEquals("vehicle_approaching|car|-|-", DedupKey.of(event(null, null)))
        assertNotEquals(
            "a band change must give a new key",
            DedupKey.of(event(Side.LEFT, DistanceBand.FAR)),
            DedupKey.of(event(Side.LEFT, DistanceBand.NEAR))
        )
    }
}
