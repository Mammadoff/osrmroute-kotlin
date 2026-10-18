package com.osrmroute
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class MobileAccessTest {
  private val app="a4ea19e0-7f48-4c6d-afd3-3c3f7ee4dff6"
  @Test fun concurrentCallsShareExchangeAndInvalidationRenews() = runBlocking {
    val server=MockWebServer(); server.start()
    val nonce="a".repeat(43)
    repeat(2) {
      server.enqueue(MockResponse().setBody("""{"challenge_id":"$app","challenge":"$nonce","enrolled":true}"""))
      server.enqueue(MockResponse().setBody("""{"access_token":"orm_mobile_fixture","expires_in":300}"""))
    }
    var proofs=0
    val broker=MobileAccess(app,"fixture-installation", { payload ->
      assertEquals(listOf("osrmroute-mobile-v1",app,app,nonce,"fixture-installation").joinToString("\n"),payload)
      proofs++; "proof"
    },server.url("/").toString().trimEnd('/'),OkHttpClient.Builder().followRedirects(false).build())
    try {
      val tokens=coroutineScope { List(8) { async { broker.token() } }.awaitAll() }
      assertTrue(tokens.all { it=="orm_mobile_fixture" }); assertEquals(2,server.requestCount); assertEquals(1,proofs)
      broker.invalidate(); broker.token(); assertEquals(4,server.requestCount); assertEquals(2,proofs)
    } finally { broker.close(); server.shutdown() }
  }
  @Test fun failedProofDoesNotReturnACredential() = runBlocking {
    val server=MockWebServer(); server.start()
    server.enqueue(MockResponse().setBody("""{"challenge_id":"$app","challenge":"${"a".repeat(43)}","enrolled":true}"""))
    val broker=MobileAccess(app,"fixture-installation", { throw IllegalStateException("attestation rejected") },
      server.url("/").toString().trimEnd('/'),OkHttpClient())
    try {
      assertFailsWith<IllegalStateException> { broker.token() }; assertEquals(1,server.requestCount)
    } finally { broker.close(); server.shutdown() }
  }
}
