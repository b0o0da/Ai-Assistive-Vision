package avn.app.data.api

import avn.app.core.consent.ConsentScope
import avn.contracts.ErrorCode
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The 9 contract endpoints (docs/api-contract.md section 5). Nothing else is ever called. */
enum class Endpoint(
    val path: String,
    val get: Boolean,
    val consent: ConsentScope?,
    /** Spoken when this endpoint fails (contracts/fixtures/api/<endpoint>.<failure>.json). */
    val fallbackMessage: String
) {
    HEALTH("health", true, null, FallbackMessages.SERVICE),
    DESCRIBE("describe", false, ConsentScope.CLOUD_FRAMES, FallbackMessages.SCENE),
    ASK("ask", false, ConsentScope.CLOUD_FRAMES, FallbackMessages.SCENE),
    SCREEN_SUMMARIZE("screen/summarize", false, ConsentScope.CLOUD_SCREEN, FallbackMessages.SCREEN),
    INTENT("intent", false, null, FallbackMessages.INTENT),
    ROUTE("route", true, null, FallbackMessages.ROUTE),
    FACES_ENROLL("faces/enroll", false, ConsentScope.FACES, FallbackMessages.FACES),
    FACES_MATCH("faces/match", false, ConsentScope.FACES, FallbackMessages.FACES_UNKNOWN),
    SOS("sos", false, null, FallbackMessages.SOS)
}

/** Spoken-safe sentences. They match the contract fixtures, which ApiClientTest checks. */
object FallbackMessages {
    const val SCENE = "I can't describe the scene right now."
    const val SCREEN = "I can't summarize this screen right now."
    const val INTENT = "I didn't understand that. Please say it another way."
    const val ROUTE = "I can't get directions right now."
    const val FACES = "I can't save this face right now."
    const val FACES_UNKNOWN = "I don't recognise this person."
    const val SOS = "I couldn't reach the server. Your SMS alert was still sent."
    const val SERVICE = "The service had a problem. Please try again shortly."
    const val UNAUTHORIZED = "I can't connect to the service. The app needs to be set up again."
    const val RATE_LIMITED = "Too many requests. Please try again in a moment."
    const val NO_CONSENT = "I need your permission before I send that to the cloud."
}

/** What every backend call returns. Callers never see HTTP, exceptions or raw envelopes. */
sealed interface ApiResult {
    /** `data` of a successful envelope, still as JSON: the caller decodes its own response type. */
    data class Success(val data: JsonElement, val requestId: String) : ApiResult

    /** Nothing usable came back. [message] is safe to speak as is. */
    data class Fallback(val message: String, val code: ErrorCode?, val requestId: String?) : ApiResult {
        /** Hand this to the engine; producers never speak directly. */
        fun toSpeechCandidate(source: Source = Source.SYSTEM): SpeechCandidate =
            SpeechCandidate(text = message, source = source, type = EventType.SYSTEM_NOTICE, userRequested = true)
    }
}

/**
 * The only door to the backend (android/CLAUDE.md rule 4). Checks consent first, sends
 * `X-API-Key`, times out at server timeout + 1 s, retries at most once, and turns every failure
 * into a spoken-safe [ApiResult.Fallback].
 *
 * [body] is the JSON request body for POST endpoints, or the query parameters for GET /route.
 */
interface ApiClient {
    suspend fun call(endpoint: Endpoint, body: JsonObject? = null): ApiResult
}

/** Lets the client ask for consent without Android types, so it runs in plain JVM tests. */
fun interface ConsentGate {
    fun isGranted(scope: ConsentScope): Boolean
}
