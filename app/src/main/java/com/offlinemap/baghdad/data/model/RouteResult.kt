package com.offlinemap.baghdad.data.model

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong

enum class RoutingSource(val displayName: String, val badgeColorHex: String) {
    GOOGLE_LIVE_TRAFFIC("Google Directions (Live Traffic)", "#2E7D32"), // Green
    SAVED_ROUTE_CACHE("Saved Route Cache (Historical Google)", "#6A1B9A"), // Purple
    OFFLINE_GRAPHHOPPER("GraphHopper 100% Offline", "#1565C0"), // Blue
    GEOMETRIC_FALLBACK("Geometric Fallback Route", "#E65100") // Orange
}

data class RouteResult(
    val points: List<LatLong>,
    val distanceMeters: Double,
    val timeMillis: Long,
    val instructions: List<RouteInstruction>,
    val boundingBox: BoundingBox? = null,
    val isFallbackCalculation: Boolean = false,
    val source: RoutingSource = RoutingSource.OFFLINE_GRAPHHOPPER,
    val trafficDelayMins: Int = 0,
    val summary: String = ""
)
