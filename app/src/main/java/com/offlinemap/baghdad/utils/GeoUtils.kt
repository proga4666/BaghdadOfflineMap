package com.offlinemap.baghdad.utils

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    const val BAGHDAD_CENTER_LAT = 33.3152
    const val BAGHDAD_CENTER_LON = 44.3661
    val BAGHDAD_CENTER = LatLong(BAGHDAD_CENTER_LAT, BAGHDAD_CENTER_LON)

    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            String.format(Locale.getDefault(), "%d m", meters.toInt())
        } else {
            val km = meters / 1000.0
            String.format(Locale.getDefault(), "%.1f km", km)
        }
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d h %d min", hours, minutes)
        } else {
            String.format(Locale.getDefault(), "%d min", max(1, minutes))
        }
    }

    fun calculateBoundingBox(points: List<LatLong>): BoundingBox? {
        if (points.isEmpty()) return null
        var minLat = points[0].latitude
        var maxLat = points[0].latitude
        var minLon = points[0].longitude
        var maxLon = points[0].longitude

        for (pt in points) {
            minLat = min(minLat, pt.latitude)
            maxLat = max(maxLat, pt.latitude)
            minLon = min(minLon, pt.longitude)
            maxLon = max(maxLon, pt.longitude)
        }

        // Add slight padding
        val latPadding = (maxLat - minLat) * 0.1
        val lonPadding = (maxLon - minLon) * 0.1

        return BoundingBox(
            max(minLat - latPadding, -85.0),
            max(minLon - lonPadding, -180.0),
            min(maxLat + latPadding, 85.0),
            min(maxLon + lonPadding, 180.0)
        )
    }

    /**
     * Haversine distance formula in meters
     */
    fun calculateDistance(p1: LatLong, p2: LatLong): Double = calculateHaversineDistance(p1, p2)

    fun calculateHaversineDistance(p1: LatLong, p2: LatLong): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
