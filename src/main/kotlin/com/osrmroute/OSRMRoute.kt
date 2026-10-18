package com.osrmroute

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Android/JVM API client. Mobile apps use their authenticated backend proxy.
 * The token provider supplies a short-lived application session, never a bundled OSRMRoute key.
 * Owns a connection pool; reuse one client, then close it when its owner is disposed.
 */
class OSRMRoute(
    baseUrl: String,
    private val tokenProvider: suspend () -> String,
    timeoutMillis: Long = 30_000,
    private val retries: Int = 0,
    allowInsecureLocalhost: Boolean = false,
) : AutoCloseable {
    private val base = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient
    init {
        require(base.isHttps || (allowInsecureLocalhost && base.host in setOf("localhost", "127.0.0.1", "::1"))) { "HTTPS required" }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "Invalid base URL" }
        require(timeoutMillis in 1..120_000 && retries in 0..2) { "Invalid request limits" }
        http = OkHttpClient.Builder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    suspend fun route(points: List<Coordinate>, profile: Profile = Profile.CAR, steps: Boolean = false): RouteResponse =
        decode(request("GET", osrm("route", points, profile), mapOf("geometries" to "geojson", "overview" to "full", "steps" to steps.toString())))
    suspend fun matrix(points: List<Coordinate>, profile: Profile = Profile.CAR): MatrixResponse =
        decode(request("GET", osrm("table", points, profile), mapOf("annotations" to "duration,distance")))
    suspend fun geocode(query: String, limit: Int = 5): GeocodeResponse =
        decode(request("GET", "/api/1/geocode", search(query, limit)))
    suspend fun reverse(point: Coordinate): GeocodeResponse =
        decode(request("GET", "/api/1/geocode", mapOf("reverse" to "true", "point" to point.point())))
    suspend fun autocomplete(query: String, limit: Int = 5): AutocompleteResponse =
        decode(request("GET", "/api/1/autocomplete", search(query, limit)))
    suspend fun places(point: Coordinate, radius: Int = 1000, category: String? = null): JsonElement {
        require(radius in 1..50_000) { "Invalid radius" }
        val query = mutableMapOf("point" to point.point(), "radius" to radius.toString())
        category?.let { query["category"] = it }
        return getJson("/api/1/places", query)
    }
    /** Extension for geographic endpoints; JSON fields retain the API's original names. */
    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement = decode(request("GET", path, query))
    /** POST is never automatically retried, including network failures and 429 responses. */
    suspend fun postJson(path: String, body: JsonElement): JsonElement = decode(request("POST", path, body = body))
    private suspend inline fun <reified T> decode(text: String): T = withContext(Dispatchers.Default) {
        try {
            json.decodeFromString<T>(text)
        } catch (_: SerializationException) {
            throw OSRMRouteException(200, "invalid_response")
        }
    }
    private fun search(query: String, limit: Int): Map<String, String> {
        require(query.isNotBlank() && query.length <= 1000 && limit in 1..50) { "Invalid search" }
        return mapOf("q" to query, "limit" to limit.toString())
    }
    private fun osrm(service: String, points: List<Coordinate>, profile: Profile): String {
        require(points.size in 2..100) { "Expected 2 to 100 points; server plan limits also apply" }
        return "/api/v1/osrm/$service/v1/${profile.path}/${points.joinToString(";") { it.path() }}"
    }
    private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: JsonElement? = null): String {
        require(Regex("^/api/(1|v1)/[A-Za-z0-9_,;./-]+$").matches(path) && !path.contains("..")) { "Invalid geographic API path" }
        require(query.keys.none { it.lowercase() in setOf("key", "api_key", "token", "authorization") }) { "Credentials belong in headers" }
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        for (attempt in 0..retries) {
            val token = tokenProvider()
            require(token.isNotBlank() && token.all { it.code in 33..126 }) { "Invalid token" }
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
            val outcome = try {
                http.newCall(request).awaitResult { response -> consumeResponse(response, method, attempt) }
            } catch (_: IOException) {
                // Unknown outcomes are not replayed, even for metered GET requests.
                throw OSRMRouteException(0, "transport_error")
            }
            outcome.text?.let { return it }
            delay(outcome.retryDelay)
        }
        error("Unreachable")
    }
    private data class Outcome(val text: String? = null, val retryDelay: Long = 0)

    private fun consumeResponse(response: Response, method: String, attempt: Int): Outcome {
        if (response.isSuccessful) {
            val source = response.body?.source() ?: throw OSRMRouteException(response.code, "invalid_response")
            if (source.request(8L * 1024 * 1024 + 1)) {
                throw OSRMRouteException(response.code, "response_too_large")
            }
            val text = source.readUtf8()
            try {
                require(text.trimStart().firstOrNull() in listOf('{', '['))
                json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                throw OSRMRouteException(response.code, "invalid_json")
            }
            return Outcome(text = text)
        }
        val transient = response.code == 429 || response.code in setOf(502, 503, 504)
        val retryAfter = response.header("Retry-After")
        val seconds = retryAfter?.toLongOrNull()
        if (method != "GET" || !transient || attempt == retries ||
            (retryAfter != null && (seconds == null || seconds !in 0..30))) {
            throw OSRMRouteException(response.code, if (response.code == 429) "rate_limited" else "http_error")
        }
        return Outcome(retryDelay = seconds?.times(1000) ?: (250L shl attempt))
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}

/** Consume on OkHttp's I/O thread before resuming; cancellation closes an in-flight body. */
private suspend fun <T> Call.awaitResult(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(consume) }
            result.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { if (!continuation.isCancelled) continuation.resumeWithException(it) },
            )
        }
    })
}
