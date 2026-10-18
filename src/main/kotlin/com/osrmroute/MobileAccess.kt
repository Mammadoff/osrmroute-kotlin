package com.osrmroute

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Pre-release hosted access; supply a Play Integrity standard-token provider.
 * The proof callback receives the exact UTF-8 payload to SHA-256/base64url as requestHash.
 * Keep keyId stable per installation; it is a public identifier, not a secret.
 */
class MobileAccess internal constructor(
    private val appId: String,
    private val keyId: String,
    private val proof: suspend (String) -> String,
    private val endpoint: String,
    private val http: OkHttpClient,
) : java.io.Closeable {
    private val lock = Mutex()
    private var cached: Pair<String, Long>? = null
    constructor(appId: String, keyId: String, proof: suspend (String) -> String) : this(
        appId, keyId, proof, "https://osrmroute.com",
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()
    )
    init {
        require(UUID.fromString(appId).toString() == appId)
        require(Regex("[A-Za-z0-9_+/=-]{16,256}").matches(keyId))
    }
    suspend fun token(): String = lock.withLock {
        cached?.takeIf { it.second - System.nanoTime() > TimeUnit.SECONDS.toNanos(30) }?.let { return@withLock it.first }
        val challenge = post("challenge", mapOf("app_id" to appId, "key_id" to keyId))
        val id = challenge["challenge_id"]?.jsonPrimitive?.content ?: throw IOException("Mobile access unavailable")
        val nonce = challenge["challenge"]?.jsonPrimitive?.content ?: throw IOException("Mobile access unavailable")
        require(UUID.fromString(id).toString() == id && Regex("[A-Za-z0-9_-]{43}").matches(nonce))
        val payload = listOf("osrmroute-mobile-v1", appId, id, nonce, keyId).joinToString("\n")
        val result = post("token", mapOf("app_id" to appId, "key_id" to keyId, "challenge_id" to id,
            "challenge" to nonce, "proof" to proof(payload)))
        val token = result["access_token"]?.jsonPrimitive?.content ?: throw IOException("Mobile access unavailable")
        val seconds = result["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        require(token.startsWith("orm_mobile_") && seconds in 1..300)
        cached = token to (System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds))
        token
    }
    suspend fun invalidate() = lock.withLock { cached = null }
    private suspend fun post(operation: String, values: Map<String, String>): JsonObject {
        val body = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
        val request = Request.Builder().url("$endpoint/api/mobile/$operation")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).awaitResult { response ->
            if (response.code != 200) throw IOException("Mobile access unavailable (${response.code})")
            val source = response.body?.source() ?: throw IOException("Mobile access unavailable")
            source.request(32769)
            if (source.buffer.size > 32768) throw IOException("Mobile response too large")
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    companion object {
        /** Use Android Base64.URL_SAFE | NO_WRAP | NO_PADDING on the returned bytes. */
        fun requestHash(payload: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    }
}
