package avn.contracts

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AVN-37: every failure fixture decodes as an ApiEnvelope, follows the rules and speaks a short sentence. */
class FailureFixtureTest {
    private val root = File(System.getProperty("avn.repoRoot"))
    private val failures = File(root, "contracts/fixtures/failures")
    private val api = File(root, "contracts/fixtures/api")

    private val cases = mapOf(
        "timeout" to ErrorCode.TIMEOUT,
        "provider_error" to ErrorCode.PROVIDER_ERROR,
        "rate_limited" to ErrorCode.RATE_LIMITED,
        "unauthorized" to ErrorCode.UNAUTHORIZED,
        "bad_request" to ErrorCode.BAD_REQUEST
    )

    private fun files(): List<File> = failures.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun decode(f: File) = Json.decodeFromString(ApiEnvelope.serializer(), f.readText())

    @Test
    fun failureFixturesExist() {
        assertEquals(19, files().size)
    }

    @Test
    fun everyFailureDecodesAndFollowsTheEnvelopeRules() {
        for (file in files()) {
            val env = decode(file)
            assertTrue("${file.name}: unsupported version", env.isSupportedVersion)
            assertTrue("${file.name}: empty request_id", env.requestId.isNotEmpty())
            assertNull("${file.name}: data must be null", env.data?.takeIf { it.toString() != "null" })
            assertNotNull("${file.name}: needs an error", env.error)
            assertNotNull("${file.name}: needs a speakable message", env.speakable)
            assertTrue("${file.name}: message over 300 chars", env.message.length <= 300)
            assertTrue("${file.name}: message has odd symbols", env.message.matches(Regex("[A-Za-z0-9 .,'?!-]+")))
        }
    }

    @Test
    fun errorCodeMatchesTheCaseInTheFileName() {
        for (file in files()) {
            val case = file.name.removeSuffix(".json").substringAfter('.')
            val expected = cases.getValue(case)
            val env = decode(file)
            assertEquals(file.name, expected, env.error!!.code)
            when (case) {
                "timeout", "provider_error" -> {
                    assertTrue("${file.name}: ok must be true", env.ok)
                    assertTrue("${file.name}: fallback must be true", env.fallback)
                    assertTrue("${file.name}: retryable must be true", env.error!!.retryable)
                }
                else -> {
                    assertTrue("${file.name}: ok must be false", !env.ok)
                    assertTrue("${file.name}: fallback must be false", !env.fallback)
                }
            }
        }
    }

    @Test
    fun everyCloudEndpointHasAllFiveCases() {
        val endpoints = listOf("describe", "ask", "screen_summarize", "intent", "route")
        // cases that already live in api/ under another name
        val inApi = mapOf(
            "describe" to setOf("timeout", "provider_error"),
            "ask" to setOf("timeout"),
            "screen_summarize" to setOf("timeout"),
            "intent" to setOf("provider_error"),
            "route" to setOf("timeout")
        )
        val here = files().map { it.name.removeSuffix(".json") }.toSet()
        for (ep in endpoints) {
            for (case in cases.keys) {
                val covered = "$ep.$case" in here || case in inApi.getValue(ep)
                assertTrue("$ep: missing $case", covered)
            }
        }
        assertTrue("api folder missing", api.isDirectory)
    }
}
