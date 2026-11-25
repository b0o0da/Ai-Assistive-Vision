package avn.app.screen

import avn.app.core.consent.ConsentGuard
import avn.app.core.consent.ConsentScope
import avn.app.core.consent.FakeConsentStore
import avn.app.data.api.BackendClient
import avn.app.data.api.BackendConfig
import avn.app.data.api.FallbackMessages
import avn.contracts.ErrorCode
import avn.contracts.ScreenFlags
import avn.contracts.ScreenSnapshot
import avn.contracts.ScreenType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WS18-02 (Notion test name: ScreenSummaryClientContractTest). The real client, through the real
 * [BackendClient], against a local server replaying contracts/fixtures/api/screen_summarize.*.
 */
class ScreenSummaryClientContractTest {
    private val api = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/api")
    private val server = MockWebServer()
    private val store = FakeConsentStore(setOf(ConsentScope.CLOUD_SCREEN))
    private val run = StringBuilder()

    private fun fixture(name: String) = File(api, name).readText()

    private fun field(name: String, vararg path: String): String {
        var node = Json.parseToJsonElement(fixture(name)).jsonObject
        for (p in path.dropLast(1)) node = node[p]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private fun reply(name: String, code: Int = 200) = MockResponse.Builder().code(code).body(fixture(name)).build()

    private fun client(serverTimeoutMs: Long = 4_000) = ApiScreenSummaryClient(
        BackendClient(
            config = BackendConfig(server.url("/").toString(), "test-key", serverTimeoutMs),
            // The HTTP layer would allow it: this proves the client's own consent check runs first.
            consent = { true }
        ),
        ConsentGuard(store)
    )

    private val request: ScreenSummaryRequest by lazy {
        val r = Json.parseToJsonElement(fixture("screen_summarize.request.json")).jsonObject
        ScreenSummaryRequest(
            packageName = r["package"]!!.jsonPrimitive.content,
            screenType = ScreenType.CHAT_LIST,
            text = r["text"]!!.jsonPrimitive.content,
            question = r["question"]!!.jsonPrimitive.content
        )
    }

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        server.close()
        val f = File("build/reports/screen-summary-fixture-run.txt")
        f.parentFile.mkdirs()
        f.appendText(run.toString())
    }

    private fun log(case: String, result: ScreenSummaryResult) {
        run.append("$case -> $result\n")
    }

    @Test
    fun successParsesSummaryAndSendsTheContractBody() = runBlocking {
        server.enqueue(reply("screen_summarize.ok.json"))
        val result = client().summarize(request)
        log("screen_summarize.ok.json", result)

        assertEquals(
            ScreenSummaryResult.Summary(field("screen_summarize.ok.json", "data", "summary"), "req_scr_1"),
            result
        )
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/screen/summarize", sent.url.encodedPath)
        assertEquals(
            Json.parseToJsonElement(fixture("screen_summarize.request.json")),
            Json.parseToJsonElement(sent.body!!.utf8())
        )
    }

    @Test
    fun timeoutEnvelopeReturnsTheFallbackMessage() = runBlocking {
        server.enqueue(reply("screen_summarize.timeout.json"))
        val result = client().summarize(request)
        log("screen_summarize.timeout.json", result)
        assertEquals(
            ScreenSummaryResult.Fallback(
                field("screen_summarize.timeout.json", "message"),
                SummaryFailure.TIMEOUT,
                ErrorCode.TIMEOUT
            ),
            result
        )
    }

    @Test
    fun clientSideTimeoutReturnsTheFallbackMessage() = runBlocking {
        server.enqueue(reply("screen_summarize.ok.json").newBuilder().headersDelay(5, TimeUnit.SECONDS).build())
        val result = client(serverTimeoutMs = 100).summarize(request)
        log("no answer within server timeout + 1 s", result)
        assertEquals(
            ScreenSummaryResult.Fallback(FallbackMessages.SCREEN, SummaryFailure.TIMEOUT, ErrorCode.TIMEOUT),
            result
        )
    }

    @Test
    fun errorFixturesBecomeSpokenFallbacks() = runBlocking {
        server.enqueue(reply("screen_summarize.blocked.json", 400))
        val blocked = client().summarize(request)
        log("screen_summarize.blocked.json", blocked)
        assertEquals(
            ScreenSummaryResult.Fallback(
                field("screen_summarize.blocked.json", "message"),
                SummaryFailure.BLOCKED,
                ErrorCode.BAD_REQUEST
            ),
            blocked
        )

        server.enqueue(reply("error.rate_limited.json", 429))
        val limited = client().summarize(request)
        log("error.rate_limited.json", limited)
        assertTrue(limited is ScreenSummaryResult.Fallback)
        assertEquals(SummaryFailure.RATE_LIMITED, (limited as ScreenSummaryResult.Fallback).failure)
        assertEquals(field("error.rate_limited.json", "message"), limited.message)
    }

    @Test
    fun consentIsCheckedFirstAndRevocationIsHonoured() = runBlocking {
        store.revoke(ConsentScope.CLOUD_SCREEN)
        val denied = client().summarize(request)
        log("consent denied", denied)
        assertEquals(
            ScreenSummaryResult.Fallback(
                ConsentGuard.deniedMessage(ConsentScope.CLOUD_SCREEN),
                SummaryFailure.NO_CONSENT,
                null
            ),
            denied
        )
        assertEquals("nothing may reach the server without consent", 0, server.requestCount)

        store.grant(ConsentScope.CLOUD_SCREEN)
        server.enqueue(reply("screen_summarize.ok.json"))
        assertTrue(client().summarize(request) is ScreenSummaryResult.Summary)
        store.revoke(ConsentScope.CLOUD_SCREEN)
        assertTrue((client().summarize(request) as ScreenSummaryResult.Fallback).failure == SummaryFailure.NO_CONSENT)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun successWithoutASummaryIsNotSpokenAsEmpty() = runBlocking {
        val body = """{"version":"1.0","ok":true,"fallback":false,"message":"",""" +
            """"data":{"summary":"  "},"request_id":"req_x","error":null}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        val result = client().summarize(request)
        log("blank summary", result)
        assertEquals(
            ScreenSummaryResult.Fallback(FallbackMessages.SCREEN, SummaryFailure.UNAVAILABLE, ErrorCode.PROVIDER_ERROR),
            result
        )
    }

    @Test
    fun requestIsBuiltFromASnapshotAndTrimmedToBackendLimits() {
        val snapshot =
            ScreenSnapshot(
                "com.whatsapp",
                ScreenType.CHAT_THREAD,
                emptyList(),
                "x".repeat(9_000),
                emptyList(),
                ScreenFlags()
            )
        val json = ScreenSummaryRequest.from(snapshot, "q".repeat(400)).toJson()
        assertEquals("chat_thread", json["screen_type"]!!.jsonPrimitive.content)
        assertEquals(ScreenSummaryRequest.MAX_TEXT, json["text"]!!.jsonPrimitive.content.length)
        assertEquals(ScreenSummaryRequest.MAX_QUESTION, json["question"]!!.jsonPrimitive.content.length)
        assertTrue("image" !in json)
    }

    @Test
    fun fakeEchoesTreeTextAndFallsBackOnBlankScreens() = runBlocking {
        val fake = FakeScreenSummaryClient()
        assertEquals(ScreenSummaryResult.Summary(request.text, null), fake.summarize(request))
        val blank = fake.summarize(request.copy(text = " "))
        assertEquals(FallbackMessages.SCREEN, (blank as ScreenSummaryResult.Fallback).message)
        assertEquals(2, fake.requests.size)
    }
}
