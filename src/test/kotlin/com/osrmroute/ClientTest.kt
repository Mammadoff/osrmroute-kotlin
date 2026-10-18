package com.osrmroute

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit

class ClientTest {
    private val server = MockWebServer()
    private fun client(retries: Int = 0, timeout: Long = 1000) = OSRMRoute(server.url("/proxy").toString(), { "session-test" }, timeout, retries, true)
    @AfterTest fun cleanup() { server.shutdown() }
    @Test fun routeCoordinatesHeadersAndModels() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"code":"Ok","routes":[{"distance":120,"duration":40,"geometry":{"type":"LineString","coordinates":[]}}]}"""))
        client().use { sdk ->
            val result = sdk.route(listOf(Coordinate(52.52, 13.405), Coordinate(48.85, 2.35)), Profile.BIKE)
            assertEquals(120.0, result.routes.single().distance)
            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/proxy/api/v1/osrm/route/v1/cycling/13.405,52.52;2.35,48.85?"))
            assertEquals("Bearer session-test", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains("session-test"))
        }
    }
    @Test fun encodedSearchAndNullableMatrix() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"hits":[{"point":{"lat":48.8,"lng":2.3},"unknown":"ignored"}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":"Ok","durations":[[0,null],[null,0]]}"""))
        client().use { sdk ->
            assertEquals(48.8, sdk.geocode("Paris & café").hits.single().point.lat)
            assertEquals("Paris & café", server.takeRequest().requestUrl!!.queryParameter("q"))
            assertNull(sdk.matrix(listOf(Coordinate(1.0,2.0),Coordinate(3.0,4.0))).durations!![0][1])
        }
    }
    @Test fun reverseAutocompletePlacesAndPost() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"hits":[]}"""))
        server.enqueue(MockResponse().setBody("""{"suggestions":[{"label":"Berlin","point":{"lat":52,"lng":13}}]}"""))
        server.enqueue(MockResponse().setBody("""{"features":[]}"""))
        server.enqueue(MockResponse().setBody("""{"hits":[]}"""))
        client().use { sdk ->
            sdk.reverse(Coordinate(52.0,13.0)); assertEquals("52.0,13.0",server.takeRequest().requestUrl!!.queryParameter("point"))
            assertEquals("Berlin",sdk.autocomplete("Ber").suggestions.single().label); server.takeRequest()
            sdk.places(Coordinate(52.0,13.0), category="cafe"); assertEquals("cafe",server.takeRequest().requestUrl!!.queryParameter("category"))
            sdk.postJson("/api/1/geocode/batch", buildJsonObject { putJsonArray("queries") { add("Paris") } })
            assertEquals("POST",server.takeRequest().method)
        }
    }
    @Test fun retriesAreOptInBoundedAndNeverPost() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","0"))
        server.enqueue(MockResponse().setBody("{}"))
        client(1).use { it.getJson("/api/1/timezone") }; assertEquals(2,server.requestCount)
        server.enqueue(MockResponse().setResponseCode(503))
        client(2).use { assertFailsWith<OSRMRouteException> { it.postJson("/api/1/geocode/batch",JsonObject(emptyMap())) } }
        assertEquals(3,server.requestCount)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","3600"))
        client(2).use { assertEquals(429,assertFailsWith<OSRMRouteException> { it.getJson("/api/1/timezone") }.status) }
        assertEquals(4,server.requestCount)
    }
    @Test fun rejectsRedirectAndRedactsErrors() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://example.invalid/steal").setBody("session-test"))
        client().use { assertEquals("OSRMRoute request failed (302, http_error)", assertFailsWith<OSRMRouteException> { it.getJson("/api/1/timezone") }.message) }
        assertEquals(1,server.requestCount)
    }
    @Test fun timeoutAndCancellationDoNotRetry() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        client(2,100).use { assertEquals(0,assertFailsWith<OSRMRouteException> { it.getJson("/api/1/timezone") }.status) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        client(2).use { sdk ->
            val job = async { sdk.getJson("/api/1/timezone") }
            delay(50); job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
    }
    @Test fun slowResponseDoesNotBlockCallerDispatcher() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(500, TimeUnit.MILLISECONDS))
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            client(timeout = 2000).use { sdk ->
                val response = async(dispatcher) { sdk.getJson("/api/1/timezone") }
                server.takeRequest(1, TimeUnit.SECONDS)
                val heartbeat = async(dispatcher) { delay(20); true }
                delay(150)
                assertTrue(heartbeat.isCompleted, "Response body read must not block the UI dispatcher")
                response.await()
            }
        }
    }
    @Test fun cancellationInterruptsSlowResponseBody() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
        client(timeout = 3000).use { sdk ->
            val response = async { sdk.getJson("/api/1/timezone") }
            delay(100)
            withTimeout(500) { response.cancelAndJoin() }
        }
    }
    @Test fun invalidInputsAndMalformedJson() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { Coordinate(Double.NaN,0.0) }
        assertFailsWith<IllegalArgumentException> { OSRMRoute("http://example.com", { "x" }) }
        client().use { sdk ->
            assertFailsWith<IllegalArgumentException> { sdk.getJson("/api/1/../admin") }
            assertFailsWith<IllegalArgumentException> { sdk.getJson("/api/1/geocode",mapOf("key" to "secret")) }
            server.enqueue(MockResponse().setBody("<html>unavailable</html>"))
            assertEquals("invalid_json",assertFailsWith<OSRMRouteException> { sdk.getJson("/api/1/geocode") }.code)
        }
    }
}
