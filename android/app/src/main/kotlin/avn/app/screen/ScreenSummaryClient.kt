package avn.app.screen

import avn.app.core.consent.ConsentCheck
import avn.app.core.consent.ConsentGuard
import avn.app.core.consent.ConsentScope
import avn.app.data.api.ApiClient
import avn.app.data.api.ApiResult
import avn.app.data.api.Endpoint
import avn.app.data.api.FallbackMessages
import avn.contracts.ErrorCode
import avn.contracts.ScreenSnapshot
import avn.contracts.ScreenType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Body of `POST /screen/summarize` (contracts/fixtures/api/screen_summarize.request.json,
 * backend `ScreenSummarizeRequest`). The image is optional: rung 3 of the WS-18 ladder only.
 */
data class ScreenSummaryRequest(
    val packageName: String,
    val screenType: ScreenType,
    val text: String,
    val question: String,
    /** Downscaled JPEG, base64. Held in memory only, never logged or stored. */
    val imageJpegBase64: String? = null
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("package", JsonPrimitive(packageName.take(MAX_PACKAGE)))
            put("screen_type", Json.encodeToJsonElement(ScreenType.serializer(), screenType))
            put("text", JsonPrimitive(text.take(MAX_TEXT)))
            imageJpegBase64?.let { put("image", JsonPrimitive(it)) }
            put("question", JsonPrimitive(question.take(MAX_QUESTION)))
        }
    )

    companion object {
        // Backend limits (backend/app/schemas/api.py ScreenSummarizeRequest); trimmed here so a long
        // screen never turns into a 400.
        const val MAX_PACKAGE = 200
        const val MAX_TEXT = 8000
        const val MAX_QUESTION = 300

        fun from(snapshot: ScreenSnapshot, question: String, imageJpegBase64: String? = null) =
            ScreenSummaryRequest(snapshot.packageName, snapshot.screenType, snapshot.text, question, imageJpegBase64)
    }
}

/** Why a summary was not produced. Lets the ladder decide whether to fall back to the tree text. */
enum class SummaryFailure { NO_CONSENT, TIMEOUT, BLOCKED, RATE_LIMITED, UNAVAILABLE }

sealed interface ScreenSummaryResult {
    /** The bounded summary from the cloud model. Informational only, never a hazard. */
    data class Summary(val text: String, val requestId: String?) : ScreenSummaryResult

    /** No summary. [message] is safe to speak as is. */
    data class Fallback(val message: String, val failure: SummaryFailure, val code: ErrorCode?) : ScreenSummaryResult
}

/**
 * WS18-02: the phone side of `POST /screen/summarize`. Interface first; the ladder (WS-18) and the
 * voice flow use this, never [ApiClient] directly.
 */
interface ScreenSummaryClient {
    suspend fun summarize(request: ScreenSummaryRequest): ScreenSummaryResult
}

/**
 * Real client. Order matters:
 * 1. Consent first: without [ConsentScope.CLOUD_SCREEN] no request is built or sent at all.
 * 2. One call through [ApiClient] (which owns the timeout, the single retry and envelope parsing).
 * 3. `data.summary` is read and capped; anything unusable becomes a spoken fallback, never an exception.
 */
@Singleton
class ApiScreenSummaryClient @Inject constructor(private val api: ApiClient, private val consent: ConsentGuard) :
    ScreenSummaryClient {
    override suspend fun summarize(request: ScreenSummaryRequest): ScreenSummaryResult {
        val check = consent.check(ConsentScope.CLOUD_SCREEN)
        if (check is ConsentCheck.Denied) {
            return ScreenSummaryResult.Fallback(check.spokenMessage, SummaryFailure.NO_CONSENT, null)
        }
        val result = runCatching { api.call(Endpoint.SCREEN_SUMMARIZE, request.toJson()) }.getOrElse {
            return unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> {
                val summary = runCatching {
                    (result.data as? JsonObject)?.get("summary")?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim()
                if (summary.isNullOrEmpty()) {
                    unavailable(ErrorCode.PROVIDER_ERROR)
                } else {
                    ScreenSummaryResult.Summary(summary.take(MAX_SUMMARY), result.requestId)
                }
            }
            is ApiResult.Fallback -> ScreenSummaryResult.Fallback(result.message, failureFor(result.code), result.code)
        }
    }

    private fun unavailable(code: ErrorCode?) =
        ScreenSummaryResult.Fallback(FallbackMessages.SCREEN, SummaryFailure.UNAVAILABLE, code)

    private fun failureFor(code: ErrorCode?): SummaryFailure = when (code) {
        ErrorCode.TIMEOUT -> SummaryFailure.TIMEOUT
        ErrorCode.BAD_REQUEST -> SummaryFailure.BLOCKED
        ErrorCode.RATE_LIMITED -> SummaryFailure.RATE_LIMITED
        else -> SummaryFailure.UNAVAILABLE
    }

    companion object {
        /** Backend `ScreenSummarizeResponse.summary` max length. */
        const val MAX_SUMMARY = 1200
    }
}

/**
 * Fake for tests and for the app before the backend is up. Returns [next] (by default a summary of
 * the request's own tree text, the same thing rung 1 would say) and records requests in memory.
 */
@Singleton
class FakeScreenSummaryClient @Inject constructor() : ScreenSummaryClient {
    var next: ScreenSummaryResult? = null
    val requests = mutableListOf<ScreenSummaryRequest>()

    override suspend fun summarize(request: ScreenSummaryRequest): ScreenSummaryResult {
        requests += request
        return next ?: if (request.text.isBlank()) {
            ScreenSummaryResult.Fallback(FallbackMessages.SCREEN, SummaryFailure.UNAVAILABLE, null)
        } else {
            ScreenSummaryResult.Summary(request.text.take(ApiScreenSummaryClient.MAX_SUMMARY), null)
        }
    }
}
