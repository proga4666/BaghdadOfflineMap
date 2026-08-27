package com.offlinemap.baghdad.engine

import android.util.Log
import com.offlinemap.baghdad.data.model.RouteInstruction
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.utils.GeoUtils
import org.mapsforge.core.model.LatLong

data class NavProgressState(
    val currentInstruction: RouteInstruction?,
    val nextInstruction: RouteInstruction?,
    val distanceToNextManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingTimeMillis: Long,
    val stepIndex: Int,
    val totalSteps: Int,
    val isOffRoute: Boolean,
    val hasArrived: Boolean
)

class NavigationTracker(private val voiceManager: VoiceGuidanceManager) {

    private var activeRoute: RouteResult? = null
    private var instructions: List<RouteInstruction> = emptyList()
    private var currentStepIdx: Int = 0
    private var isNavigating: Boolean = false

    private var hasAnnouncedAdvance = false
    private var hasAnnouncedImminent = false
    private var hasAnnouncedExecute = false
    private var lastOffRouteTriggerTime = 0L

    var onNavStateChanged: ((NavProgressState) -> Unit)? = null
    var onOffRoute: (() -> Unit)? = null
    var onArrival: (() -> Unit)? = null

    val isTracking: Boolean
        get() = isNavigating

    fun startNavigation(route: RouteResult) {
        activeRoute = route
        instructions = route.instructions
        currentStepIdx = 0
        isNavigating = true
        hasAnnouncedAdvance = false
        hasAnnouncedImminent = false
        hasAnnouncedExecute = false

        Log.i("NavigationTracker", "Started Turn-by-Turn Navigation with ${instructions.size} steps.")

        val firstInst = instructions.firstOrNull()
        voiceManager.speakDeparture(firstInst, GeoUtils.formatDistance(route.distanceMeters))

        emitCurrentState(distanceToManeuver = firstInst?.distanceMeters ?: 0.0, remainingDistance = route.distanceMeters, remainingTime = route.timeMillis, isOffRoute = false, hasArrived = false)
    }

    fun stopNavigation() {
        isNavigating = false
        activeRoute = null
        instructions = emptyList()
        currentStepIdx = 0
        voiceManager.stop()
        Log.i("NavigationTracker", "Stopped Turn-by-Turn Navigation.")
    }

    fun onLocationUpdate(userLoc: LatLong, speedMps: Float = 0f) {
        if (!isNavigating) return
        val route = activeRoute ?: return
        if (instructions.isEmpty()) return

        // 1. Check Arrival at final destination point
        val destPoint = route.points.lastOrNull()
        if (destPoint != null) {
            val distToDest = GeoUtils.calculateDistance(userLoc, destPoint)
            if (distToDest <= 30.0 || (currentStepIdx >= instructions.size - 1 && distToDest <= 45.0)) {
                voiceManager.speakArrival(route.summary)
                isNavigating = false
                onArrival?.invoke()
                emitCurrentState(0.0, 0.0, 0L, isOffRoute = false, hasArrived = true)
                return
            }
        }

        // 2. Check Off-Route Deviation (> 65m from any point on polyline)
        var minPolyDistance = Double.MAX_VALUE
        for (pt in route.points) {
            val d = GeoUtils.calculateDistance(userLoc, pt)
            if (d < minPolyDistance) minPolyDistance = d
        }

        if (minPolyDistance > 65.0) {
            val now = System.currentTimeMillis()
            if (now - lastOffRouteTriggerTime > 12000L) { // Prevent alert spam
                lastOffRouteTriggerTime = now
                Log.w("NavigationTracker", "Off-route detected! Distance from path: ${minPolyDistance.toInt()}m")
                voiceManager.speakRecalculating()
                onOffRoute?.invoke()
                emitCurrentState(0.0, 0.0, 0L, isOffRoute = true, hasArrived = false)
            }
            return
        }

        // 3. Maneuver Evaluation
        val curInst = instructions.getOrNull(currentStepIdx) ?: return
        val maneuverLoc = curInst.location ?: estimateManeuverLocation(currentStepIdx)
        val distToManeuver = if (maneuverLoc != null) {
            GeoUtils.calculateDistance(userLoc, maneuverLoc)
        } else {
            curInst.distanceMeters
        }

        // Voice trigger logic
        if (distToManeuver in 280.0..450.0 && !hasAnnouncedAdvance) {
            hasAnnouncedAdvance = true
            voiceManager.speakAdvanceTurn(curInst, distToManeuver.toInt())
        } else if (distToManeuver in 65.0..140.0 && !hasAnnouncedImminent) {
            hasAnnouncedImminent = true
            voiceManager.speakAdvanceTurn(curInst, distToManeuver.toInt())
        } else if (distToManeuver <= 25.0 && !hasAnnouncedExecute) {
            hasAnnouncedExecute = true
            voiceManager.speakExecuteTurn(curInst)
        }

        // Advance to next instruction when passing maneuver
        if (distToManeuver < 20.0 && currentStepIdx < instructions.size - 1) {
            currentStepIdx++
            hasAnnouncedAdvance = false
            hasAnnouncedImminent = false
            hasAnnouncedExecute = false
            Log.i("NavigationTracker", "Advanced to next navigation step: $currentStepIdx / ${instructions.size}")
        }

        // Calculate remaining trip distance and time from current step
        var remainingDist = distToManeuver
        for (i in (currentStepIdx + 1) until instructions.size) {
            remainingDist += instructions[i].distanceMeters
        }
        val remainingTime = (route.timeMillis * (remainingDist / route.distanceMeters.coerceAtLeast(1.0))).toLong()

        emitCurrentState(
            distanceToManeuver = distToManeuver,
            remainingDistance = remainingDist,
            remainingTime = remainingTime,
            isOffRoute = false,
            hasArrived = false
        )
    }

    private fun estimateManeuverLocation(stepIdx: Int): LatLong? {
        val points = activeRoute?.points ?: return null
        if (points.isEmpty()) return null
        val frac = stepIdx.toDouble() / instructions.size.coerceAtLeast(1)
        val pIdx = (frac * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
        return points[pIdx]
    }

    private fun emitCurrentState(
        distanceToManeuver: Double,
        remainingDistance: Double,
        remainingTime: Long,
        isOffRoute: Boolean,
        hasArrived: Boolean
    ) {
        val cur = instructions.getOrNull(currentStepIdx)
        val next = instructions.getOrNull(currentStepIdx + 1)
        val state = NavProgressState(
            currentInstruction = cur,
            nextInstruction = next,
            distanceToNextManeuverMeters = distanceToManeuver,
            remainingDistanceMeters = remainingDistance,
            remainingTimeMillis = remainingTime,
            stepIndex = currentStepIdx,
            totalSteps = instructions.size,
            isOffRoute = isOffRoute,
            hasArrived = hasArrived
        )
        onNavStateChanged?.invoke(state)
    }
}
