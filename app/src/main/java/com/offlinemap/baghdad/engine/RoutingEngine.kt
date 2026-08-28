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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val loadMutex = Mutex()

    /**
     * Initialize GraphHopper with custom Google-biased edge weighting
     */
    suspend fun loadRoutingGraph(graphFolder: File): Boolean = withContext(Dispatchers.IO) {
        if (isHopperLoaded && hopper != null) return@withContext true
        loadMutex.withLock {
            if (isHopperLoaded && hopper != null) return@withLock true
            try {
                if (!graphFolder.exists() || !graphFolder.isDirectory) {
                    return@withLock false
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

                // Clean up any corrupt 2-point straight line entries
                cacheManager.clearCorruptRoutes()

                // Re-train GraphHopper on all previously cached Google routes!
                val allCachedPoints = cacheManager.getAllSavedRoutePoints()
                for (pts in allCachedPoints) {
                    learnRoadSegmentsFromGoogleRoute(pts)
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                isHopperLoaded = false
                false
            }
        }
    }

    /**
     * Map-matches polyline coordinates to GraphHopper road edge IDs with dense 10m interpolation
     * and registers every intermediate road block as a preferred Google corridor.
     */
    fun learnRoadSegmentsFromGoogleRoute(points: List<LatLong>, vehicle: String = "car") {
        val currentHopper = hopper ?: return
        if (!isHopperLoaded) return
        if (points.size < 2) return

        try {
            val locationIndex = currentHopper.locationIndex ?: return
            val encoder = try {
                currentHopper.encodingManager.getEncoder(vehicle)
            } catch (e: Exception) {
                currentHopper.encodingManager.getEncoder("car")
            }
            val edgeFilter = com.graphhopper.routing.util.DefaultEdgeFilter.allEdges(encoder)
            val matchedEdges = HashSet<Int>()

            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val dist = GeoUtils.calculateDistance(p1, p2)
                val steps = kotlin.math.max(1, (dist / 10.0).toInt())

                for (s in 0..steps) {
                    val frac = s.toDouble() / steps
                    val lat = p1.latitude + (p2.latitude - p1.latitude) * frac
                    val lon = p1.longitude + (p2.longitude - p1.longitude) * frac

                    val qr = locationIndex.findClosest(lat, lon, edgeFilter)
                    if (qr.isValid && qr.closestEdge != null) {
                        matchedEdges.add(qr.closestEdge.edge)
                    }
                }
            }

            if (matchedEdges.isNotEmpty()) {
                edgeStore.addLearnedEdges(matchedEdges)
                Log.i("RoutingEngine", "Learned ${matchedEdges.size} road segments from Google Route (10m continuous sampling). Total learned edges: ${edgeStore.getLearnedEdgesCount()}")
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

        // Check local spatial cache for previously learned exact Google trips
        val cachedRoute = cacheManager.findMatchingRoute(start, dest, vehicle, maxDistanceMeters = 80.0)
        if (cachedRoute != null && cachedRoute.points.size >= 5) {
            Log.i("RoutingEngine", "Found exact matching route in local Spatial Route Cache!")
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
                    val instLoc = if (inst.points != null && inst.points.size() > 0) {
                        LatLong(inst.points.getLat(0), inst.points.getLon(0))
                    } else null
                    val lanes = generateLanesForInstruction(inst.sign, inst.name)
                    instructions.add(
                        RouteInstruction(
                            text = turnDesc,
                            distanceMeters = inst.distance,
                            timeMillis = inst.time,
                            sign = inst.sign,
                            streetName = inst.name,
                            location = instLoc,
                            lanes = lanes
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

    private fun generateLanesForInstruction(sign: Int, streetName: String): List<com.offlinemap.baghdad.data.model.LaneInfo>? {
        val isMajorAvenueOrHighway = streetName.contains("شارع", ignoreCase = true) ||
                streetName.contains("طريق", ignoreCase = true) ||
                streetName.contains("سريع", ignoreCase = true) ||
                streetName.contains("جسر", ignoreCase = true) ||
                streetName.contains("Street", ignoreCase = true) ||
                streetName.contains("Highway", ignoreCase = true) ||
                streetName.contains("Expressway", ignoreCase = true) ||
                streetName.contains("Bridge", ignoreCase = true) ||
                streetName.contains("Road", ignoreCase = true) ||
                streetName.contains("Avenue", ignoreCase = true)

        if (!isMajorAvenueOrHighway && sign == 0) return null

        return when (sign) {
            -2, -3 -> listOf( // Turn Left / Sharp Left
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = false)
            )
            -1 -> listOf( // Slight Left / Keep Left
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = false)
            )
            2, 3 -> listOf( // Turn Right / Sharp Right
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = true)
            )
            1 -> listOf( // Slight Right / Exit / Ramp
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = true)
            )
            0 -> if (isMajorAvenueOrHighway) listOf( // Straight on major road
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = false)
            ) else null
            -6, 6 -> listOf( // U-Turn
                com.offlinemap.baghdad.data.model.LaneInfo("left", isActive = true),
                com.offlinemap.baghdad.data.model.LaneInfo("through", isActive = false),
                com.offlinemap.baghdad.data.model.LaneInfo("right", isActive = false)
            )
            else -> null
        }
    }

    fun close() {
        hopper?.close()
        hopper = null
        isHopperLoaded = false
    }
}
