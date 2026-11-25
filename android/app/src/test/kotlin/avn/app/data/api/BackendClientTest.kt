package avn.app.data.api

import avn.contracts.ErrorCode
import avn.contracts.EventType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WS03-08: the client against a local server replaying the contract fixtures. */
class BackendClientTest {
    private val api = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/api")
    private val server = MockWebServer()
    private val logs = mutableListOf<String>()
    private var granted = true

    private fun fixture(name: String) = File(api, name).readText()

    private fun message(name: String) =
        Json.parseToJsonElement(fixture(name)).jsonObject["message"]!!.jsonPrimitive.content

    private fun body(name: String) = Json.parseToJsonElement(fixture(name)).jsonObject

    private fun reply(name: String, code: Int = 200) = MockResponse.Builder().code(code).body(fixture(name)).build()

    private fun client(serverTimeoutMs: Long = 4_000) = BackendClient(
        config = BackendConfig(server.url("/").toString(), "test-key", serverTimeoutMs),
        consent = { granted },
        log = { logs += it }
    )

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun successReturnsTheEnvelopeDataAndSendsTheKey() = runBlocking {
        server.enqueue(reply("describe.ok.json"))
        val result = client().call(Endpoint.DESCRIBE, body("describe.request.json"))

        assertTrue(result is ApiResult.Success)
        val expected = Json.parseToJsonElement(fixture("describe.ok.json")).jsonObject["data"]
        assertEquals(expected, (result as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/describe", request.url.encodedPath)
        assertEquals("test-key", request.headers["X-API-Key"])
    }

    @Test
    fun healthIsCalledWithoutTheKey() = runBlocking {
        server.enqueue(reply("health.ok.json"))
        assertTrue(client().call(Endpoint.HEALTH) is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertNull(request.headers["X-API-Key"])
    }

    @Test
    fun routeSendsQueryParameters() = runBlocking {
        server.enqueue(reply("route.ok.json"))
        client().call(Endpoint.ROUTE, body("route.request.json"))
        val url = server.takeRequest().url
        assertEquals("/route", url.encodedPath)
        assertEquals("walking", url.queryParameter("mode"))
        assertEquals("nearest pharmacy", url.queryParameter("destination"))
    }

    @Test
    fun serverFallbackIsSpokenAsIs() = runBlocking {
        server.enqueue(reply("ask.timeout.json"))
        val result = client().call(Endpoint.ASK, body("ask.request.json")) as ApiResult.Fallback
        assertEquals(message("ask.timeout.json"), result.message)
        assertEquals(ErrorCode.TIMEOUT, result.code)
    }

    @Test
    fun unauthorizedEnvelopeMapsToItsMessage() = runBlocking {
        server.enqueue(reply("error.unauthorized.json", 401))
        val result = client().call(Endpoint.INTENT, body("intent.request.json")) as ApiResult.Fallback
        assertEquals(message("error.unauthorized.json"), result.message)
        assertEquals(ErrorCode.UNAUTHORIZED, result.code)
    }

    @Test
    fun statusWithoutEnvelopeStillMapsToTheContractMessages() = runBlocking {
        val cases =
            listOf(
                401 to "error.unauthorized.json",
                429 to "error.rate_limited.json"
            )
        for ((code, name) in cases) {
            server.enqueue(MockResponse.Builder().code(code).body("<html>proxy</html>").build())
            val result = client().call(Endpoint.INTENT, body("intent.request.json")) as ApiResult.Fallback
            assertEquals("$code", message(name), result.message)
        }
    }

    @Test
    fun clientTimesOutAtServerTimeoutPlusOneSecondWithoutRetrying() = runBlocking {
        server.enqueue(reply("ask.ok.json").newBuilder().headersDelay(5, TimeUnit.SECONDS).build())
        val result = client(
            serverTimeoutMs = 100
        ).call(Endpoint.ASK, body("ask.request.json")) as ApiResult.Fallback
        assertEquals(message("ask.timeout.json"), result.message)
        assertEquals(ErrorCode.TIMEOUT, result.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun droppedConnectionIsRetriedOnce() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(reply("sos.ok.json"))
        val result = client().call(Endpoint.SOS, body("sos.request.json"))
        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun twoDroppedConnectionsGiveTheEndpointFallback() = runBlocking {
        repeat(2) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }
        val result = client().call(Endpoint.SOS, body("sos.request.json")) as ApiResult.Fallback
        assertEquals(message("sos.provider_error.json"), result.message)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noConsentMeansNoNetworkCall() = runBlocking {
        granted = false
        val result = client().call(Endpoint.DESCRIBE, body("describe.request.json")) as ApiResult.Fallback
        assertEquals(FallbackMessages.NO_CONSENT, result.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unsupportedEnvelopeVersionFallsBack() = runBlocking {
        val raw = fixture("describe.ok.json").replace("\"version\": \"1.0\"", "\"version\": \"9.9\"")
        server.enqueue(MockResponse.Builder().body(raw).build())
        assertTrue(client().call(Endpoint.DESCRIBE, body("describe.request.json")) is ApiResult.Fallback)
    }

    @Test
    fun logsHoldMetadataOnly() = runBlocking {
        server.enqueue(reply("ask.ok.json"))
        val question = body("ask.request.json")["question"]!!.jsonPrimitive.content
        client().call(Endpoint.ASK, body("ask.request.json"))
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.contains(question) || it.contains("base64") })
    }

    @Test
    fun fallbackMessagesMatchTheContractFixtures() {
        assertEquals(message("describe.timeout.json"), Endpoint.DESCRIBE.fallbackMessage)
        assertEquals(message("ask.timeout.json"), Endpoint.ASK.fallbackMessage)
        assertEquals(message("screen_summarize.timeout.json"), Endpoint.SCREEN_SUMMARIZE.fallbackMessage)
        assertEquals(message("intent.fallback.json"), Endpoint.INTENT.fallbackMessage)
        assertEquals(message("route.fallback.json"), Endpoint.ROUTE.fallbackMessage)
        assertEquals(message("sos.provider_error.json"), Endpoint.SOS.fallbackMessage)
        assertEquals(message("error.unauthorized.json"), FallbackMessages.UNAUTHORIZED)
        assertEquals(message("error.rate_limited.json"), FallbackMessages.RATE_LIMITED)
    }

    @Test
    fun fallbackBecomesAUserRequestedSpeechCandidate() {
        val candidate = ApiResult.Fallback("Hello.", null, null).toSpeechCandidate()
        assertEquals("Hello.", candidate.text)
        assertEquals(EventType.SYSTEM_NOTICE, candidate.type)
        assertTrue(candidate.userRequested)
    }

    @Test
    fun requestBodyIsTheGivenJson() = runBlocking {
        server.enqueue(reply("intent.ok.json"))
        client().call(Endpoint.INTENT, JsonObject(mapOf("text" to JsonPrimitive("open whatsapp"))))
        assertEquals("""{"text":"open whatsapp"}""", server.takeRequest().body?.utf8())
    }
}
