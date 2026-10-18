package com.osrmroute

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Named latitude/longitude; OSRM path conversion happens inside the client. */
data class Coordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }
    internal fun path() = "$longitude,$latitude"
    internal fun point() = "$latitude,$longitude"
}
enum class Profile(internal val path: String) { CAR("driving"), BIKE("cycling"), FOOT("walking") }
@Serializable data class RouteResponse(val code: String, val routes: List<Route>, val waypoints: List<JsonElement> = emptyList())
@Serializable data class Route(val distance: Double, val duration: Double, val geometry: JsonElement? = null, val legs: List<JsonElement> = emptyList())
@Serializable data class MatrixResponse(val code: String, val durations: List<List<Double?>>? = null, val distances: List<List<Double?>>? = null)
@Serializable data class Point(val lat: Double, val lng: Double)
@Serializable data class GeocodeHit(val point: Point, val name: String? = null, val country: String? = null, val city: String? = null, val street: String? = null, val postcode: String? = null)
@Serializable data class GeocodeResponse(val hits: List<GeocodeHit>)
@Serializable data class Suggestion(val label: String, val point: Point, val name: String? = null, val city: String? = null, val country: String? = null)
@Serializable data class AutocompleteResponse(val suggestions: List<Suggestion>)
/** Does not include response bodies, tokens, coordinates or request URLs. */
class OSRMRouteException(val status: Int, val code: String) : Exception("OSRMRoute request failed ($status, $code)")
