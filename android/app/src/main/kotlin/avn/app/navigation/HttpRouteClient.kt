package avn.app.navigation

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.ApiEnvelope
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parses a `/route` response's `data` object (per `route.ok.json` / `api.schema.json`) into a
 * [RouteResult]. Pulled out of [HttpRouteClient] as a top-level `internal` function, rather than a
 * private method, so a plain JUnit test can exercise it directly against the frozen fixtures in
 * `contracts/fixtures/api/` without needing an [okhttp3.OkHttpClient] or [JsonLinesLogger].
 */
internal fun parseRouteData(data: JsonObject): RouteResult {
    val steps = data["steps"]?.jsonArray.orEmpty().map { stepEl ->
        val s = stepEl.jsonObject
        RouteStep(
            instruction = s["instruction"]?.jsonPrimitive?.contentOrNull ?: "",
            maneuver = s["maneuver"]?.jsonPrimitive?.contentOrNull ?: "straight",
            distanceMeters = s["distance_m"]?.jsonPrimitive?.double ?: 0.0
        )
    }
    return RouteResult(
        steps = steps,
        distanceMeters = data["distance_m"]?.jsonPrimitive?.double ?: steps.sumOf { it.distanceMeters },
        durationSeconds = data["duration_s"]?.jsonPrimitive?.double ?: 0.0,
        destinationName = data["destination_name"]?.jsonPrimitive?.contentOrNull
    )
}

/**
 * Real [RouteClient]: `GET /route?origin=lat,lon&destination=<query>&mode=walking`, wrapped in
 * [ApiEnvelope] like every other backend call (android/CLAUDE.md architecture rule 4 -- timeout,
 * one retry, spoken-safe fallback). Response shape matches `route.ok.json` /
 * `contracts/schemas/api.schema.json`, not the older client-only draft this started from (see
 * `docs/decisions/` -- no entry was needed, the contract was already frozen under AVN-34).
 *
 * `source` ("ocr" | "voice" | "manual") is deliberately **not** sent: the frozen contract has no
 * such field. Provenance is logged locally only, via [log], for debugging misread rates --
 * exactly the fallback TD-006 itself called out if the backend chose to drop it.
 */
class HttpRouteClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val log: JsonLinesLogger,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RouteClient {

    override suspend fun getRoute(origin: LocationFix, destinationQuery: String): RouteResult =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/route".toHttpUrl().newBuilder()
                .addQueryParameter("origin", "${origin.lat},${origin.lon}")
                .addQueryParameter("destination", destinationQuery)
                .addQueryParameter("mode", "walking")
                .build()
            val request = Request.Builder().url(url).header("X-API-Key", apiKey).get().build()

            val envelope = executeWithOneRetry(request)
            val data = envelope.data
            if (!envelope.ok || data == null) {
                log.log("navigation", "route_rejected", fields = mapOf("fallback" to envelope.fallback))
                throw RouteRequestException(envelope.message.ifBlank { "Backend rejected the route request" })
            }
            parseRouteData(data.jsonObject)
        }

    private fun executeWithOneRetry(request: Request): ApiEnvelope {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (body.isEmpty()) throw IOException("Empty response body from /route")
                    return json.decodeFromString(ApiEnvelope.serializer(), body)
                }
            } catch (e: Exception) {
                lastError = e
                log.log("navigation", "route_attempt_failed", fields = mapOf("attempt" to attempt, "error" to e.javaClass.simpleName))
            }
        }
        throw lastError ?: RouteRequestException("Unreachable")
    }
}
