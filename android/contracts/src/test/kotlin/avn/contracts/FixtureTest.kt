package avn.contracts

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 covers the fixtures whose models exist; Phase 2 extends this to every file in index.json. */
class FixtureTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures")

    /** Files in contracts/fixtures/api that are requests, not envelopes. */
    private val requestFiles = setOf("describe.request.json", "intent.request.json")

    private fun envelopeFiles(): List<File> =
        File(fixtures, "api").listFiles { f -> f.extension == "json" && f.name !in requestFiles }
            .orEmpty()
            .sortedBy { it.name }

    @Test
    fun eventFixturesRoundTrip() {
        val files = File(fixtures, "events").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no event fixtures found in $fixtures", files.isNotEmpty())
        for (file in files) {
            val event = Json.decodeFromString(Event.serializer(), file.readText())
            val again = Json.decodeFromString(Event.serializer(), Json.encodeToString(Event.serializer(), event))
            assertEquals(file.name, event, again)
        }
    }

    @Test
    fun eventFixturesCoverWs0201Samples() {
        val count = File(fixtures, "events").listFiles { f -> f.extension == "json" }.orEmpty().size
        assertTrue("expected at least 6 event fixtures, found $count", count >= 6)
    }

    @Test
    fun eventRejectsMetreAndUnknownFields() {
        val sample = File(fixtures, "events/obstacle_ahead_medium.json").readText()
        for (field in listOf("distance_m", "meters", "speed", "ttc")) {
            val withExtra = sample.trimEnd().removeSuffix("}") + ", \"$field\": 3 }"
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(Event.serializer(), withExtra)
            }
        }
    }

    @Test
    fun eventRejectsValuesOutsideTheEnums() {
        val sample = File(fixtures, "events/obstacle_ahead_medium.json").readText()
        val badSide = sample.replace("\"side\": \"center\"", "\"side\": \"behind\"")
        val badBand = sample.replace("\"distance_band\": \"medium\"", "\"distance_band\": \"2m\"")
        listOf(badSide, badBand).forEach { json ->
            assertThrows(SerializationException::class.java) { Json.decodeFromString(Event.serializer(), json) }
        }
    }

    @Test
    fun enumWireValuesMatchCommonSchema() {
        val sides = Side.entries.map { Json.encodeToString(Side.serializer(), it).trim('"') }
        val bands = DistanceBand.entries.map { Json.encodeToString(DistanceBand.serializer(), it).trim('"') }
        assertEquals(listOf("left", "center", "right"), sides)
        assertEquals(listOf("near", "medium", "far"), bands)
    }

    @Test
    fun screenFixturesParse() {
        val files = File(fixtures, "screens").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no screen fixtures found in $fixtures", files.isNotEmpty())
        files.forEach { Json.decodeFromString(ScreenSnapshot.serializer(), it.readText()) }
    }

    @Test
    fun envelopeFixturesParseAndFollowRules() {
        val files = envelopeFiles()
        assertTrue("no envelope fixtures found in $fixtures/api", files.isNotEmpty())
        for (file in files) {
            val env = Json.decodeFromString(ApiEnvelope.serializer(), file.readText())
            assertTrue("${file.name}: unsupported version", env.isSupportedVersion)
            assertTrue("${file.name}: request_id is empty", env.requestId.isNotEmpty())
            if (!env.ok) assertNotNull("${file.name}: ok=false needs an error", env.error)
            if (!env.ok || env.fallback) {
                assertNotNull("${file.name}: needs a speakable message", env.speakable)
            } else {
                assertNull("${file.name}: success should not be speakable", env.speakable)
            }
        }
    }

    @Test
    fun envelopeSurvivesRoundTrip() {
        for (file in envelopeFiles()) {
            val env = Json.decodeFromString(ApiEnvelope.serializer(), file.readText())
            val again = Json.decodeFromString(
                ApiEnvelope.serializer(),
                Json.encodeToString(ApiEnvelope.serializer(), env)
            )
            assertEquals(file.name, env, again)
        }
    }

    @Test
    fun allFiveErrorCodesDecode() {
        val expected = mapOf(
            "timeout" to ErrorCode.TIMEOUT,
            "rate_limited" to ErrorCode.RATE_LIMITED,
            "unauthorized" to ErrorCode.UNAUTHORIZED,
            "bad_request" to ErrorCode.BAD_REQUEST,
            "provider_error" to ErrorCode.PROVIDER_ERROR
        )
        for ((wire, code) in expected) {
            val info = Json.decodeFromString(ErrorInfo.serializer(), """{"code":"$wire","retryable":false}""")
            assertEquals(wire, code, info.code)
        }
        assertEquals(5, ErrorCode.values().size)
    }

    @Test
    fun wrongVersionIsDetected() {
        val raw =
            """
            {"version":"9.9","ok":true,"fallback":false,"message":"",
             "data":{},"request_id":"r1","error":null}
            """.trimIndent()
        val env = Json.decodeFromString(ApiEnvelope.serializer(), raw)
        assertFalse(env.isSupportedVersion)
    }
}
