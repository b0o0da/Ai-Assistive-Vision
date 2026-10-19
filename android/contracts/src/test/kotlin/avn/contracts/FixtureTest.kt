package avn.contracts

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 covers the fixtures whose models exist; Phase 2 extends this to every file in index.json. */
class FixtureTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures")

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
}
