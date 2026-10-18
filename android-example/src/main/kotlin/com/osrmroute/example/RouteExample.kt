package com.osrmroute.example

import com.osrmroute.Coordinate
import com.osrmroute.OSRMRoute

/** Invoke from a lifecycle-owned coroutine with an authenticated backend session. */
suspend fun routeDistance(applicationSessionToken: String): Double {
    return OSRMRoute("https://your-backend.example/location", { applicationSessionToken }).use { client ->
        client.route(listOf(Coordinate(52.52, 13.405), Coordinate(52.50, 13.39))).routes.first().distance
    }
}
