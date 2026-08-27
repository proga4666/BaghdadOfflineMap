package com.offlinemap.baghdad.data.network

import android.text.Html
import com.offlinemap.baghdad.data.model.RouteInstruction
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.data.model.RoutingSource
import com.offlinemap.baghdad.utils.PolylineDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class GoogleDirectionsService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun getTrafficRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String,
        apiKey: String
    ): Result<RouteResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Google Maps API Key is empty. Please set it in Settings."))
        }

        val mode = when (vehicle.lowercase()) {
            "car", "drive" -> "driving"
            "bike", "bicycle" -> "bicycling"
            "foot", "walk" -> "walking"
            else -> "driving"
        }

        val originParam = "${start.latitude},${start.longitude}"
        val destParam = "${dest.latitude},${dest.longitude}"

        val urlBuilder = StringBuilder("https://maps.googleapis.com/maps/api/directions/json?")
            .append("origin=").append(originParam)
            .append("&destination=").append(destParam)
            .append("&mode=").append(mode)
            .append("&key=").append(apiKey)

        // Only driving mode supports departure_time=now & traffic_model
        if (mode == "driving") {
            urlBuilder.append("&departure_time=now&traffic_model=best_guess")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", "BaghdadOfflineMap/1.0")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Google API HTTP Error: ${response.code}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response body from Google API"))
            val json = JSONObject(body)

            val status = json.optString("status", "UNKNOWN_ERROR")
            if (status != "OK") {
                val errorMsg = json.optString("error_message", "Google Directions returned status: $status")
                return@withContext Result.failure(IOException(errorMsg))
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) {
                return@withContext Result.failure(IOException("No route found between selected points"))
            }

            val route0 = routes.getJSONObject(0)
            val summary = route0.optString("summary", "")
            val overviewPolyline = route0.getJSONObject("overview_polyline").getString("points")
            val points = PolylineDecoder.decode(overviewPolyline)

            val legs = route0.getJSONArray("legs")
            val leg0 = legs.getJSONObject(0)

            val distanceMeters = leg0.getJSONObject("distance").getDouble("value")
            val durationNormalSec = leg0.getJSONObject("duration").getLong("value")
            val durationTrafficSec = if (leg0.has("duration_in_traffic")) {
                leg0.getJSONObject("duration_in_traffic").getLong("value")
            } else {
                durationNormalSec
            }

            val trafficDelayMins = max(0, ((durationTrafficSec - durationNormalSec) / 60).toInt())
            val timeMillis = durationTrafficSec * 1000L

            // Parse detailed steps
            val instructions = ArrayList<RouteInstruction>()
            val steps = leg0.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val rawHtml = step.optString("html_instructions", "Proceed")
                val cleanText = Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                val stepDist = step.getJSONObject("distance").getDouble("value")
                val stepDurationSec = step.getJSONObject("duration").getLong("value")
                val maneuver = step.optString("maneuver", "")
                val sign = parseManeuverToSign(maneuver)

                instructions.add(
                    RouteInstruction(
                        text = cleanText,
                        distanceMeters = stepDist,
                        timeMillis = stepDurationSec * 1000L,
                        sign = sign,
                        streetName = cleanText
                    )
                )
            }

            // Calculate bounding box
            val boundingBox = if (points.isNotEmpty()) {
                var minLat = Double.MAX_VALUE
                var maxLat = -Double.MAX_VALUE
                var minLon = Double.MAX_VALUE
                var maxLon = -Double.MAX_VALUE
                for (p in points) {
                    minLat = min(minLat, p.latitude)
                    maxLat = max(maxLat, p.latitude)
                    minLon = min(minLon, p.longitude)
                    maxLon = max(maxLon, p.longitude)
                }
                BoundingBox(minLat, minLon, maxLat, maxLon)
            } else null

            val result = RouteResult(
                points = points,
                distanceMeters = distanceMeters,
                timeMillis = timeMillis,
                instructions = instructions,
                boundingBox = boundingBox,
                isFallbackCalculation = false,
                source = RoutingSource.GOOGLE_LIVE_TRAFFIC,
                trafficDelayMins = trafficDelayMins,
                summary = summary
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseManeuverToSign(maneuver: String): Int {
        return when (maneuver.lowercase(Locale.ROOT)) {
            "turn-slight-left" -> -1
            "turn-left" -> -2
            "turn-sharp-left" -> -3
            "turn-slight-right" -> 1
            "turn-right" -> 2
            "turn-sharp-right" -> 3
            "uturn-left", "uturn-right" -> 6
            "straight" -> 0
            else -> 0
        }
    }
}
