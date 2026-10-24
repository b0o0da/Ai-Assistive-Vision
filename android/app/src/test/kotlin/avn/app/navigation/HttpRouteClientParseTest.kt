package avn.app.navigation

import avn.contracts.ApiEnvelope
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [parseRouteData] against the frozen `/route` fixtures in `contracts/fixtures/api/`
 * (same `avn.repoRoot` convention as :contracts' FixtureTest), so a change to the parsing logic
 * that drifts from the frozen contract shape fails here instead of only at runtime.
 */
class HttpRouteClientParseTest {
    private val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/api")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRouteOkFixtureIntoRouteResult() {
        val envelope = json.decodeFromString(ApiEnvelope.serializer(), File(fixtures, "route.ok.json").readText())
        assertTrue(envelope.ok)
        val result = parseRouteData(envelope.data!!.jsonObject)

        assertEquals(3, result.steps.size)
        assertEquals("Head north on the street", result.steps[0].instruction)
        assertEquals("start", result.steps[0].maneuver)
        assertEquals(120.0, result.steps[0].distanceMeters, 0.0)
        assertEquals("turn_right", result.steps[1].maneuver)
        assertEquals("arrive", result.steps[2].maneuver)
        assertEquals(200.0, result.distanceMeters, 0.0)
        assertEquals(160.0, result.durationSeconds, 0.0)
        assertEquals("Pharmacy (example)", result.destinationName)
    }

    @Test
    fun missingDistanceAndDurationFallBackToStepSumAndZero() {
        val minimal = Json.parseToJsonElement(
            """{"steps":[{"instruction":"Go","maneuver":"start","distance_m":50}]}"""
        ).jsonObject
        val result = parseRouteData(minimal)

        assertEquals(50.0, result.distanceMeters, 0.0)
        assertEquals(0.0, result.durationSeconds, 0.0)
        assertNull(result.destinationName)
    }

    @Test
    fun missingStepsYieldsEmptyStepsAndZeroDistance() {
        val empty = Json.parseToJsonElement("""{}""").jsonObject
        val result = parseRouteData(empty)

        assertTrue(result.steps.isEmpty())
        assertEquals(0.0, result.distanceMeters, 0.0)
    }

    @Test
    fun routeFallbackFixtureHasNoDataToParse() {
        val envelope = json.decodeFromString(ApiEnvelope.serializer(), File(fixtures, "route.fallback.json").readText())
        assertTrue(envelope.fallback)
        assertNull(envelope.data)
    }
}
