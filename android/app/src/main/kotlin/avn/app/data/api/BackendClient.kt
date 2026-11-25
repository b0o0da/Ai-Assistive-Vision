package avn.app.data.api

import avn.contracts.ApiEnvelope
import avn.contracts.ErrorCode
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Backend address and per-install key. Provider keys never live on the phone. */
data class BackendConfig(
    val baseUrl: String,
    val apiKey: String,
    /** Must match the server's PROVIDER_TIMEOUT_S; the client waits one second longer. */
    val serverTimeoutMs: Long = 4_000
)

/**
 * OkHttp implementation of [ApiClient].
 *
 * Logs metadata only (endpoint, status, latency, request id), never bodies, images, text,
 * embeddings or coordinates.
 */
class BackendClient(
    private val config: BackendConfig,
    private val consent: ConsentGate,
    http: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val newRequestId: () -> String = { "req_" + UUID.randomUUID().toString().replace("-", "").take(16) }
) : ApiClient {
    private val http: OkHttpClient =
        http.newBuilder()
            .callTimeout(config.serverTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // this class owns the single retry
            .build()

    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun call(endpoint: Endpoint, body: JsonObject?): ApiResult {
        val scope = endpoint.consent
        if (scope != null && !consent.isGranted(scope)) {
            return ApiResult.Fallback(FallbackMessages.NO_CONSENT, null, null)
        }
        val requestId = newRequestId()
        val request = build(endpoint, body, requestId)
        return withContext(io) {
            try {
                execute(endpoint, request)
            } catch (first: IOException) {
                if (first is InterruptedIOException) {
                    // Timed out: waiting twice would leave the user in silence too long.
                    timeout(endpoint, requestId)
                } else {
                    // Connection dropped before an answer: retry once.
                    try {
                        execute(endpoint, request)
                    } catch (second: IOException) {
                        if (second is InterruptedIOException) {
                            timeout(endpoint, requestId)
                        } else {
                            log("""{"request_id":"$requestId","endpoint":"${endpoint.path}","outcome":"network"}""")
                            ApiResult.Fallback(endpoint.fallbackMessage, ErrorCode.PROVIDER_ERROR, requestId)
                        }
                    }
                }
            }
        }
    }

    private fun timeout(endpoint: Endpoint, requestId: String): ApiResult {
        log("""{"request_id":"$requestId","endpoint":"${endpoint.path}","outcome":"timeout"}""")
        return ApiResult.Fallback(endpoint.fallbackMessage, ErrorCode.TIMEOUT, requestId)
    }

    private fun build(endpoint: Endpoint, body: JsonObject?, requestId: String): Request {
        val base = config.baseUrl.trimEnd('/') + "/" + endpoint.path
        val builder =
            Request.Builder()
                .header("X-Request-Id", requestId)
                .header("Accept", "application/json")
        if (endpoint != Endpoint.HEALTH) builder.header("X-API-Key", config.apiKey)
        return if (endpoint.get) {
            val url = base.toHttpUrl().newBuilder()
            body?.forEach { (key, value) -> url.addQueryParameter(key, (value as? JsonPrimitive)?.content) }
            builder.url(url.build()).get().build()
        } else {
            val payload = (body ?: JsonObject(emptyMap())).toString()
            builder.url(base).post(payload.toRequestBody(JSON_MEDIA)).build()
        }
    }

    private fun execute(endpoint: Endpoint, request: Request): ApiResult {
        val started = System.nanoTime()
        http.newCall(request).execute().use { response ->
            val raw = response.body.string()
            val requestId = request.header("X-Request-Id")
            val ms = (System.nanoTime() - started) / 1_000_000
            log(
                """{"request_id":"$requestId","endpoint":"${endpoint.path}","status":${response.code},"latency_ms":$ms}"""
            )
            val env = runCatching { json.decodeFromString(ApiEnvelope.serializer(), raw) }.getOrNull()
            return if (env == null) fromStatus(endpoint, response.code, requestId) else fromEnvelope(endpoint, env)
        }
    }

    private fun fromEnvelope(endpoint: Endpoint, env: ApiEnvelope): ApiResult {
        if (!env.isSupportedVersion) {
            return ApiResult.Fallback(FallbackMessages.SERVICE, ErrorCode.PROVIDER_ERROR, env.requestId)
        }
        val data = env.data
        if (env.ok && !env.fallback && data != null && data !is JsonNull) {
            return ApiResult.Success(data, env.requestId)
        }
        return ApiResult.Fallback(env.speakable ?: endpoint.fallbackMessage, env.error?.code, env.requestId)
    }

    /** No envelope came back (a proxy page, an empty body): decide from the HTTP status alone. */
    private fun fromStatus(endpoint: Endpoint, status: Int, requestId: String?): ApiResult = when (status) {
        401, 403 -> ApiResult.Fallback(FallbackMessages.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, requestId)
        429 -> ApiResult.Fallback(FallbackMessages.RATE_LIMITED, ErrorCode.RATE_LIMITED, requestId)
        408, 504 -> ApiResult.Fallback(endpoint.fallbackMessage, ErrorCode.TIMEOUT, requestId)
        else -> ApiResult.Fallback(endpoint.fallbackMessage, ErrorCode.PROVIDER_ERROR, requestId)
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
