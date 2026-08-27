package com.offlinemap.baghdad.utils

import org.mapsforge.core.model.LatLong

object PolylineDecoder {

    /**
     * Decode a Google encoded polyline string into a list of LatLong coordinates
     * Uses standard Polyline Algorithm Format (5-bit chunks with ASCII offset 63)
     */
    fun decode(encodedPath: String): List<LatLong> {
        val poly = ArrayList<LatLong>()
        var index = 0
        val len = encodedPath.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encodedPath[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encodedPath[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latDouble = lat.toDouble() / 1E5
            val lngDouble = lng.toDouble() / 1E5
            poly.add(LatLong(latDouble, lngDouble))
        }

        return poly
    }
}
