# OSRMRoute Kotlin SDK 0.1.0

Android API 21+ / JVM. Requires JDK 17 to build; Kotlin 2.1.10, Gradle 8.11.1.
Uses OkHttp, kotlinx.coroutines and kotlinx.serialization (pinned in build file).
## Install from Maven Central

Add `mavenCentral()` to your dependency repositories, then in your app
`build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.osrmroute:osrmroute-kotlin:0.1.0")
}
```

Public source: https://github.com/Mammadoff/osrmroute-kotlin

A source ZIP is also available at
https://osrmroute.com/downloads/osrmroute-kotlin-0.1.0.zip.

Android requires `<uses-permission android:name="android.permission.INTERNET" />`.
Call suspend methods from a lifecycle-managed coroutine; never block the UI thread.

```kotlin
import com.osrmroute.OSRMRoute
import com.osrmroute.Coordinate

suspend fun routeDistance(applicationSessionToken: String): Double {
    return OSRMRoute("https://your-backend.example/location", { applicationSessionToken }).use { client ->
        client.route(listOf(Coordinate(52.52, 13.405), Coordinate(52.50, 13.39))).routes.first().distance
    }
}
```

For repeated requests, reuse a client and close it on owner disposal. Handle
`OSRMRouteException.status` and `.code`; let `CancellationException` propagate.
Typed decoding ignores unknown fields but rejects missing required fields.

```sh
gradle test --no-daemon --max-workers=2
```

## Security and backend contract

Mobile apps must use their own authenticated backend. Do not bundle a private
OSRMRoute API key in a binary, source file or remote configuration. `baseURL` /
`baseUrl` can include a proxy prefix: `/location` becomes
`/location/api/1/geocode`. The app token provider returns your application's
short-lived session. The backend must authenticate that session, allowlist
geographic endpoints, enforce per-user quotas, strip inbound Authorization,
and inject its server-held OSRMRoute key. Do not implement an unrestricted URL
proxy. Client restrictions are not server authorization.

HTTPS is required. HTTP is allowed only with explicit `allowInsecureLocalhost`
for loopback tests. Redirects are disabled; credentials never move to a redirect
destination. Tokens go in headers, never query strings. Errors omit response
bodies and request URLs. The SDK does not log requests or credentials.

## API contract

- Named coordinates are latitude then longitude; route/matrix paths convert to
  longitude,latitude. Reverse/Places use latitude,longitude query values.
- `route`, `matrix`, `geocode`, `reverse`, `autocomplete`: typed responses.
- `places`: JSON response preserving provider/API fields.
- Geographic GET/POST extension methods support `/api/1/` and `/api/v1/` paths.
  They do not expose account, billing or administrative operations.
- Distances are metres, durations seconds. Null matrix cells are unreachable.
- Point helpers accept 2–100 coordinates. Endpoint and plan limits still apply.
- The library is an API client, not a map renderer, GPS service, offline map
  engine or turn-by-turn navigation SDK. Real-device acceptance is separate.

## Failure policy

Default timeout 30 seconds (configurable up to 120). Retry count defaults to zero
because requests may consume credits. Opt-in retries (maximum 2) apply only to
GET HTTP 429/502/503/504. Retry-After numeric seconds up to 30 are respected;
longer, invalid and HTTP-date values return the error without retry. Missing
Retry-After uses 250ms, then 500ms. POST and transport failures are never replayed.
A caller cancellation interrupts the request or retry delay. A successful HTTP
response with an incompatible payload is a decoding error, not a retry trigger.

Server entitlements, credits, coverage and freshness are unchanged. No account
or payment operation is performed by constructing the client.
