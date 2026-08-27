package com.offlinemap.baghdad.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.offlinemap.baghdad.data.model.RouteInstruction
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.data.model.RoutingSource
import com.offlinemap.baghdad.utils.GeoUtils
import com.offlinemap.baghdad.utils.PolylineDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import kotlin.math.max
import kotlin.math.min

class RouteCacheManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ROUTES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_START_LAT REAL,
                $COL_START_LON REAL,
                $COL_DEST_LAT REAL,
                $COL_DEST_LON REAL,
                $COL_VEHICLE TEXT,
                $COL_DISTANCE REAL,
                $COL_DURATION INTEGER,
                $COL_TRAFFIC_DELAY INTEGER,
                $COL_SUMMARY TEXT,
                $COL_POINTS_TEXT TEXT,
                $COL_INSTRUCTIONS_JSON TEXT,
                $COL_TIMESTAMP INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_coords ON $TABLE_ROUTES($COL_START_LAT, $COL_START_LON, $COL_DEST_LAT, $COL_DEST_LON)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ROUTES")
        onCreate(db)
    }

    fun saveRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String,
        route: RouteResult
    ) {
        try {
            val db = writableDatabase

            val pointsStr = route.points.joinToString(";") { "${it.latitude},${it.longitude}" }
            val instructionsArray = JSONArray()
            for (inst in route.instructions) {
                val obj = JSONObject()
                obj.put("text", inst.text)
                obj.put("dist", inst.distanceMeters)
                obj.put("time", inst.timeMillis)
                obj.put("sign", inst.sign)
                obj.put("street", inst.streetName)
                instructionsArray.put(obj)
            }

            val values = ContentValues().apply {
                put(COL_START_LAT, start.latitude)
                put(COL_START_LON, start.longitude)
                put(COL_DEST_LAT, dest.latitude)
                put(COL_DEST_LON, dest.longitude)
                put(COL_VEHICLE, vehicle.lowercase())
                put(COL_DISTANCE, route.distanceMeters)
                put(COL_DURATION, route.timeMillis)
                put(COL_TRAFFIC_DELAY, route.trafficDelayMins)
                put(COL_SUMMARY, route.summary)
                put(COL_POINTS_TEXT, pointsStr)
                put(COL_INSTRUCTIONS_JSON, instructionsArray.toString())
                put(COL_TIMESTAMP, System.currentTimeMillis())
            }

            db.insertWithOnConflict(TABLE_ROUTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Search spatial cache for a previously saved route starting and ending within threshold meters (e.g. 150m)
     */
    fun findMatchingRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String,
        maxDistanceMeters: Double = 150.0
    ): RouteResult? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_ROUTES WHERE $COL_VEHICLE = ? ORDER BY $COL_TIMESTAMP DESC LIMIT 100",
            arrayOf(vehicle.lowercase())
        )

        try {
            while (cursor.moveToNext()) {
                val sLat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_START_LAT))
                val sLon = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_START_LON))
                val dLat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DEST_LAT))
                val dLon = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DEST_LON))

                val distStart = GeoUtils.calculateDistance(start, LatLong(sLat, sLon))
                val distDest = GeoUtils.calculateDistance(dest, LatLong(dLat, dLon))

                if (distStart <= maxDistanceMeters && distDest <= maxDistanceMeters) {
                    // Match found!
                    val distanceMeters = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE))
                    val durationMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION))
                    val trafficDelay = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TRAFFIC_DELAY))
                    val summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUMMARY)) ?: ""
                    val pointsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_POINTS_TEXT))
                    val instructionsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_INSTRUCTIONS_JSON))

                    val points = ArrayList<LatLong>()
                    pointsStr.split(";").forEach { pair ->
                        val parts = pair.split(",")
                        if (parts.size == 2) {
                            val lat = parts[0].toDoubleOrNull()
                            val lon = parts[1].toDoubleOrNull()
                            if (lat != null && lon != null) {
                                points.add(LatLong(lat, lon))
                            }
                        }
                    }

                    val instructions = ArrayList<RouteInstruction>()
                    val jsonArray = JSONArray(instructionsJson)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        instructions.add(
                            RouteInstruction(
                                text = obj.getString("text"),
                                distanceMeters = obj.getDouble("dist"),
                                timeMillis = obj.getLong("time"),
                                sign = obj.getInt("sign"),
                                streetName = obj.optString("street", "")
                            )
                        )
                    }

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

                    return RouteResult(
                        points = points,
                        distanceMeters = distanceMeters,
                        timeMillis = durationMillis,
                        instructions = instructions,
                        boundingBox = boundingBox,
                        isFallbackCalculation = false,
                        source = RoutingSource.SAVED_ROUTE_CACHE,
                        trafficDelayMins = trafficDelay,
                        summary = summary
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor.close()
        }

        return null
    }

    /**
     * Search spatial cache for any previously saved Google route that leads to the same destination
     * and whose corridor passes nearby the new start point, seamlessly stitching the exact Google route!
     */
    fun findCorridorMatchingRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String,
        maxDestDistanceMeters: Double = 180.0,
        maxCorridorDistanceMeters: Double = 600.0
    ): RouteResult? {
        val exactMatch = findMatchingRoute(start, dest, vehicle, maxDistanceMeters = 150.0)
        if (exactMatch != null) return exactMatch

        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_ROUTES WHERE $COL_VEHICLE = ? ORDER BY $COL_TIMESTAMP DESC LIMIT 100",
            arrayOf(vehicle.lowercase())
        )

        try {
            while (cursor.moveToNext()) {
                val dLat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DEST_LAT))
                val dLon = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DEST_LON))

                val distDest = GeoUtils.calculateDistance(dest, LatLong(dLat, dLon))
                if (distDest <= maxDestDistanceMeters) {
                    val pointsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_POINTS_TEXT))
                    val points = ArrayList<LatLong>()
                    pointsStr.split(";").forEach { pair ->
                        val parts = pair.split(",")
                        if (parts.size == 2) {
                            val lat = parts[0].toDoubleOrNull()
                            val lon = parts[1].toDoubleOrNull()
                            if (lat != null && lon != null) {
                                points.add(LatLong(lat, lon))
                            }
                        }
                    }

                    if (points.size < 4) continue

                    // Find closest point on corridor to new start point
                    var closestIdx = -1
                    var minDistance = Double.MAX_VALUE
                    for (i in 0 until points.size - 1) {
                        val d = GeoUtils.calculateDistance(start, points[i])
                        if (d < minDistance) {
                            minDistance = d
                            closestIdx = i
                        }
                    }

                    if (minDistance <= maxCorridorDistanceMeters && closestIdx in 0 until points.size - 2) {
                        // Sliced sub-corridor found!
                        val remainingPoints = ArrayList<LatLong>()
                        remainingPoints.add(start)
                        remainingPoints.addAll(points.subList(closestIdx, points.size))

                        var calculatedDistance = 0.0
                        for (i in 0 until remainingPoints.size - 1) {
                            calculatedDistance += GeoUtils.calculateDistance(remainingPoints[i], remainingPoints[i + 1])
                        }

                        val fullDuration = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION))
                        val fullDistance = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE)).coerceAtLeast(100.0)
                        val estimatedDuration = (fullDuration * (calculatedDistance / fullDistance)).toLong().coerceAtLeast(60000L)
                        val trafficDelay = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TRAFFIC_DELAY))

                        val instructionsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_INSTRUCTIONS_JSON))
                        val instructions = ArrayList<RouteInstruction>()
                        val jsonArray = JSONArray(instructionsJson)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            instructions.add(
                                RouteInstruction(
                                    text = obj.getString("text"),
                                    distanceMeters = obj.getDouble("dist"),
                                    timeMillis = obj.getLong("time"),
                                    sign = obj.getInt("sign"),
                                    streetName = obj.optString("street", "")
                                )
                            )
                        }

                        val boundingBox = if (remainingPoints.isNotEmpty()) {
                            var minLat = Double.MAX_VALUE
                            var maxLat = -Double.MAX_VALUE
                            var minLon = Double.MAX_VALUE
                            var maxLon = -Double.MAX_VALUE
                            for (p in remainingPoints) {
                                minLat = min(minLat, p.latitude)
                                maxLat = max(maxLat, p.latitude)
                                minLon = min(minLon, p.longitude)
                                maxLon = max(maxLon, p.longitude)
                            }
                            BoundingBox(minLat, minLon, maxLat, maxLon)
                        } else null

                        return RouteResult(
                            points = remainingPoints,
                            distanceMeters = calculatedDistance,
                            timeMillis = estimatedDuration,
                            instructions = instructions,
                            boundingBox = boundingBox,
                            isFallbackCalculation = false,
                            source = RoutingSource.SAVED_ROUTE_CACHE,
                            trafficDelayMins = trafficDelay,
                            summary = "Google Learned Corridor (Re-used Offline)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor.close()
        }

        return null
    }

    fun getAllSavedRoutePoints(): List<List<LatLong>> {
        val result = ArrayList<List<LatLong>>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COL_POINTS_TEXT FROM $TABLE_ROUTES ORDER BY $COL_TIMESTAMP DESC LIMIT 100", null)
        try {
            while (cursor.moveToNext()) {
                val pointsStr = cursor.getString(0) ?: continue
                val points = ArrayList<LatLong>()
                pointsStr.split(";").forEach { pair ->
                    val parts = pair.split(",")
                    if (parts.size == 2) {
                        val lat = parts[0].toDoubleOrNull()
                        val lon = parts[1].toDoubleOrNull()
                        if (lat != null && lon != null) {
                            points.add(LatLong(lat, lon))
                        }
                    }
                }
                if (points.isNotEmpty()) {
                    result.add(points)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor.close()
        }
        return result
    }

    fun getCachedRoutesCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ROUTES", null)
        return try {
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    companion object {
        const val DATABASE_NAME = "route_cache.db"
        const val DATABASE_VERSION = 1

        const val TABLE_ROUTES = "cached_routes"
        const val COL_ID = "id"
        const val COL_START_LAT = "start_lat"
        const val COL_START_LON = "start_lon"
        const val COL_DEST_LAT = "dest_lat"
        const val COL_DEST_LON = "dest_lon"
        const val COL_VEHICLE = "vehicle"
        const val COL_DISTANCE = "distance"
        const val COL_DURATION = "duration"
        const val COL_TRAFFIC_DELAY = "traffic_delay"
        const val COL_SUMMARY = "summary"
        const val COL_POINTS_TEXT = "points_text"
        const val COL_INSTRUCTIONS_JSON = "instructions_json"
        const val COL_TIMESTAMP = "timestamp"
    }
}
