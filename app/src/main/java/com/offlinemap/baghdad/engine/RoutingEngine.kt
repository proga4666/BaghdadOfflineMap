package com.offlinemap.baghdad.engine

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.PathWrapper
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.routing.util.FlagEncoder
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph
import com.graphhopper.routing.util.HintsMap
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.offlinemap.baghdad.data.cache.LearnedEdgeStore
import com.offlinemap.baghdad.data.cache.RouteCacheManager
import com.offlinemap.baghdad.data.model.RouteInstruction
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.data.model.RoutingSource
import com.offlinemap.baghdad.data.network.GoogleDirectionsService
import com.offlinemap.baghdad.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import java.io.File
import java.util.Locale
import kotlin.math.sin

enum class PreferredRoutingProvider(val title: String) {
    AUTO("🔄 Auto (Online Traffic / Offline Backup)"),
    GOOGLE_TRAFFIC("🟢 Google Directions (Live Traffic)"),
    OFFLINE_GRAPHHOPPER("⚡ GraphHopper (100% Offline)")
}

class RoutingEngine(private val context: Context) {

    private var hopper: GraphHopper? = null
    var isHopperLoaded = false
        private set

    val googleService = GoogleDirectionsService()
    val cacheManager = RouteCacheManager(context)
    val edgeStore = LearnedEdgeStore(context)

    /**
     * Initialize GraphHopper with custom Google-biased edge weighting
     */
    suspend fun loadRoutingGraph(graphFolder: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!graphFolder.exists() || !graphFolder.isDirectory) {
                return@withContext false
            }

            close()

            val instance = object : GraphHopper() {
                override fun createWeighting(hintsMap: HintsMap, encoder: FlagEncoder, graph: Graph): Weighting {
                    return GoogleBiasedWeighting(encoder, hintsMap, edgeStore)
                }
            }

            instance.forMobile()
            instance.setCHEnabled(false)
            instance.setEncodingManager(com.graphhopper.routing.util.EncodingManager.create("car,bike,foot"))
            instance.load(graphFolder.absolutePath)
            hopper = instance
            isHopperLoaded = true
            Log.i("RoutingEngine", "GraphHopper offline routing successfully loaded from ${graphFolder.absolutePath}")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isHopperLoaded = false
            false
        }
    }

    /**
     * Map-matches polyline coordinates to GraphHopper road edge IDs and registers them as preferred corridors
     */
    fun learnRoadSegmentsFromGoogleRoute(points: List<LatLong>) {
        val currentHopper = hopper ?: return
        if (!isHopperLoaded) return

        try {
            val locationIndex = currentHopper.locationIndex ?: return
            val matchedEdges = HashSet<Int>()

            for (pt in points) {
                val qr = locationIndex.findClosest(pt.latitude, pt.longitude, EdgeFilter.ALL_EDGES)
                if (qr.isValid && qr.closestEdge != null) {
                    matchedEdges.add(qr.closestEdge.edge)
                }
            }

            if (matchedEdges.isNotEmpty()) {
                edgeStore.addLearnedEdges(matchedEdges)
                Log.i("RoutingEngine", "Learned ${matchedEdges.size} road segments from Google Route. Total learned edges: ${edgeStore.getLearnedEdgesCount()}")
            }
        } catch (e: Exception) {
            Log.e("RoutingEngine", "Error matching Google route to GraphHopper edges", e)
        }
    }

    /**
     * Calculate route with hybrid strategy:
     * 1. Google Directions with Live Traffic (if online & enabled)
     * 2. Saved Spatial Route Cache (if exact/nearby route found)
     * 3. GraphHopper 100% Offline Engine with Google-Biased Edge Weighting
     * 4. Fallback geometry simulation (if outside graph bounds)
     */
    suspend fun calculateRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String = "car",
        provider: PreferredRoutingProvider = PreferredRoutingProvider.AUTO,
        googleApiKey: String = "",
        isOnline: Boolean = true
    ): Result<RouteResult> = withContext(Dispatchers.IO) {

        val shouldTryGoogle = (provider == PreferredRoutingProvider.GOOGLE_TRAFFIC || 
                              (provider == PreferredRoutingProvider.AUTO && isOnline)) && 
                              googleApiKey.isNotBlank()

        if (shouldTryGoogle) {
            Log.d("RoutingEngine", "Attempting route calculation via Google Directions API (Live Traffic)...")
            val googleResult = googleService.getTrafficRoute(start, dest, vehicle, googleApiKey)
            if (googleResult.isSuccess) {
                val route = googleResult.getOrThrow()
                // Save trip in spatial cache
                cacheManager.saveRoute(start, dest, vehicle, route)
                // Learn individual road segments so ANY future offline trip prefers these corridors
                learnRoadSegmentsFromGoogleRoute(route.points)
                Log.i("RoutingEngine", "Successfully retrieved and learned from Google Traffic route.")
                return@withContext Result.success(route)
            } else {
                Log.w("RoutingEngine", "Google API failed (${googleResult.exceptionOrNull()?.message}). Falling back to Offline Cache/GraphHopper.")
            }
        }

        // Check local spatial cache for previously learned exact/nearby Google trips
        val cachedRoute = cacheManager.findMatchingRoute(start, dest, vehicle)
        if (cachedRoute != null) {
            Log.i("RoutingEngine", "Found matching route in local Spatial Route Cache!")
            return@withContext Result.success(cachedRoute)
        }

        // Calculate using GraphHopper Offline Engine with Google-biased corridor weighting!
        val currentHopper = hopper
        if (currentHopper != null && isHopperLoaded) {
            try {
                val req = GHRequest(start.latitude, start.longitude, dest.latitude, dest.longitude)
                    .setVehicle(vehicle)
                    .setWeighting("google_biased")
                    .setLocale(Locale.getDefault())

                val rsp: GHResponse = currentHopper.route(req)

                if (rsp.hasErrors()) {
                    val errorMsg = rsp.errors.joinToString("; ") { it.message ?: "Unknown routing error" }
                    Log.w("RoutingEngine", "GraphHopper routing error: $errorMsg. Falling back to geometric preview.")
                    return@withContext Result.success(generateFallbackRoute(start, dest, vehicle))
                }

                val path: PathWrapper = rsp.best
                val pointList: PointList = path.points
                val latLongs = ArrayList<LatLong>(pointList.size())
                for (i in 0 until pointList.size()) {
                    latLongs.add(LatLong(pointList.getLat(i), pointList.getLon(i)))
                }

                val tr = (currentHopper.translationMap ?: com.graphhopper.util.TranslationMap().doImport())
                    .getWithFallBack(Locale.getDefault())

                val instructions = ArrayList<RouteInstruction>()
                val ghInstructions: InstructionList = path.instructions
                for (inst: Instruction in ghInstructions) {
                    val turnDesc = inst.getTurnDescription(tr)
                    instructions.add(
                        RouteInstruction(
                            text = turnDesc,
                            distanceMeters = inst.distance,
                            timeMillis = inst.time,
                            sign = inst.sign,
                            streetName = inst.name
                        )
                    )
                }

                val boundingBox = if (latLongs.isNotEmpty()) {
                    var minLat = Double.MAX_VALUE
                    var maxLat = -Double.MAX_VALUE
                    var minLon = Double.MAX_VALUE
                    var maxLon = -Double.MAX_VALUE
                    for (p in latLongs) {
                        minLat = kotlin.math.min(minLat, p.latitude)
                        maxLat = kotlin.math.max(maxLat, p.latitude)
                        minLon = kotlin.math.min(minLon, p.longitude)
                        maxLon = kotlin.math.max(maxLon, p.longitude)
                    }
                    BoundingBox(minLat, minLon, maxLat, maxLon)
                } else null

                val hasLearnedSegments = edgeStore.getLearnedEdgesCount() > 0
                val routeResult = RouteResult(
                    points = latLongs,
                    distanceMeters = path.distance,
                    timeMillis = path.time,
                    instructions = instructions,
                    boundingBox = boundingBox,
                    isFallbackCalculation = false,
                    source = RoutingSource.OFFLINE_GRAPHHOPPER,
                    trafficDelayMins = 0,
                    summary = if (hasLearnedSegments) "Offline (Google-Trained Corridors)" else "Offline (Baghdad Road Network)"
                )

                return@withContext Result.success(routeResult)
            } catch (e: Exception) {
                Log.e("RoutingEngine", "GraphHopper execution exception", e)
            }
        }

        // Ultimate fallback
        Result.success(generateFallbackRoute(start, dest, vehicle))
    }

    private fun generateFallbackRoute(
        start: LatLong,
        dest: LatLong,
        vehicle: String
    ): RouteResult {
        val points = ArrayList<LatLong>()
        points.add(start)

        val directDistance = GeoUtils.calculateDistance(start, dest)
        val stepsCount = 10

        val dLat = (dest.latitude - start.latitude) / stepsCount
        val dLon = (dest.longitude - start.longitude) / stepsCount

        for (i in 1 until stepsCount) {
            val baseLat = start.latitude + dLat * i
            val baseLon = start.longitude + dLon * i
            val offsetFactor = sin(i * Math.PI / stepsCount) * 0.0008
            val perpLat = -dLon * offsetFactor
            val perpLon = dLat * offsetFactor
            points.add(LatLong(baseLat + perpLat, baseLon + perpLon))
        }
        points.add(dest)

        val speedKmh = when (vehicle.lowercase()) {
            "bike" -> 15.0
            "foot" -> 4.5
            else -> 35.0
        }
        val estimatedDistance = directDistance * 1.3
        val durationMillis = ((estimatedDistance / (speedKmh * 1000.0 / 3600.0)) * 1000.0).toLong()

        val instructions = listOf(
            RouteInstruction("Head toward destination", estimatedDistance * 0.4, (durationMillis * 0.4).toLong(), 0, "Baghdad Road"),
            RouteInstruction("Turn right onto main avenue", estimatedDistance * 0.3, (durationMillis * 0.3).toLong(), 2, "Avenue"),
            RouteInstruction("Turn left toward destination", estimatedDistance * 0.3, (durationMillis * 0.3).toLong(), -2, "Target Way"),
            RouteInstruction("You have arrived at your destination", 0.0, 0L, 4, "Destination")
        )

        val boundingBox = BoundingBox(
            kotlin.math.min(start.latitude, dest.latitude) - 0.002,
            kotlin.math.min(start.longitude, dest.longitude) - 0.002,
            kotlin.math.max(start.latitude, dest.latitude) + 0.002,
            kotlin.math.max(start.longitude, dest.longitude) + 0.002
        )

        return RouteResult(
            points = points,
            distanceMeters = estimatedDistance,
            timeMillis = durationMillis,
            instructions = instructions,
            boundingBox = boundingBox,
            isFallbackCalculation = true,
            source = RoutingSource.GEOMETRIC_FALLBACK,
            trafficDelayMins = 0,
            summary = "Direct Offline Route"
        )
    }

    fun close() {
        hopper?.close()
        hopper = null
        isHopperLoaded = false
    }
}
