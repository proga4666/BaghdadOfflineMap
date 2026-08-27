package com.offlinemap.baghdad.engine

import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.offlinemap.baghdad.R
import com.offlinemap.baghdad.utils.GeoUtils
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.graphics.AndroidSvgBitmapStore
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.layer.queue.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik

enum class MapThemePreset(val title: String, val assetPath: String?) {
    WAZE_DARK("🚗 Waze Dark (Clean & Minimal)", "themes/theme_waze_dark.xml"),
    WAZE_LIGHT("☀️ Waze Light (Clean & Minimal)", "themes/theme_waze_light.xml"),
    MODERN_LIGHT("🗺️ Google Maps Clean Style", "themes/theme_light_modern.xml"),
    MIDNIGHT_DARK("🌙 Midnight Dark (High-Contrast)", "themes/theme_dark_midnight.xml"),
    OSM_CLASSIC("🧭 OSM Standard Classic", null)
}

class MapEngine(
    private val context: Context,
    val mapView: MapView
) {

    private var tileCache: TileCache? = null
    private var tileRendererLayer: TileRendererLayer? = null
    private var tileDownloadLayer: TileDownloadLayer? = null
    private var mapDataStore: MapDataStore? = null

    var isOfflineModeLoaded: Boolean = false
        private set

    // Overlays
    private var startMarker: Marker? = null
    private var destMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var routeOutlinePolyline: Polyline? = null

    // Callbacks
    var onMapTapListener: ((LatLong) -> Unit)? = null
    var onMapLongClickListener: ((LatLong) -> Unit)? = null

    private var userLocationMarker: Marker? = null

    var currentThemePreset: MapThemePreset = MapThemePreset.WAZE_DARK
        private set

    init {
        setupMapView()
        setupGestureDetector()
    }

    private fun applyThemeBackgroundColor(preset: MapThemePreset? = currentThemePreset) {
        val safePreset = preset ?: MapThemePreset.WAZE_DARK
        val bgColor = when (safePreset) {
            MapThemePreset.WAZE_DARK -> Color.parseColor("#1E232B")
            MapThemePreset.WAZE_LIGHT -> Color.parseColor("#F5F3EF")
            MapThemePreset.MODERN_LIGHT -> Color.parseColor("#F2EFE9")
            MapThemePreset.MIDNIGHT_DARK -> Color.parseColor("#121212")
            MapThemePreset.OSM_CLASSIC -> Color.parseColor("#F2EFE9")
        }
        mapView.setBackgroundColor(bgColor)
        try {
            mapView.model.displayModel.backgroundColor = bgColor
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupMapView() {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true
        mapView.setBuiltInZoomControls(true)
        mapView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        applyThemeBackgroundColor(currentThemePreset)

        // Center on Baghdad & Al-Mansour with street-level zoom
        mapView.model.mapViewPosition.setCenter(GeoUtils.BAGHDAD_CENTER)
        mapView.model.mapViewPosition.zoomLevel = 14.toByte()
    }

    var mapTilt: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 55f)
            mapView.cameraDistance = 8000f * context.resources.displayMetrics.density
            mapView.rotationX = field
            mapView.repaint()
        }

    private enum class TwoFingerMode { NONE, ROTATING, TILTING }
    private var activeTwoFingerMode = TwoFingerMode.NONE
    private var initialPointerAngle = 0.0
    private var initialMapRotation = 0f
    private var initialMidY = 0f
    private var initialY0 = 0f
    private var initialY1 = 0f
    private var initialTilt = 0f
    private var isTwoFingerGestureActive = false

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = false

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (trackingMode != TrackingMode.FREE && !isTrackingSuspended) {
                    isTrackingSuspended = true
                    onTrackingSuspensionChanged?.invoke(true)
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (trackingMode != TrackingMode.FREE && !isTrackingSuspended) {
                    isTrackingSuspended = true
                    onTrackingSuspensionChanged?.invoke(true)
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val point = Point(e.x.toDouble(), e.y.toDouble())
                val latLong = mapView.mapViewProjection.fromPixels(point.x, point.y)
                if (latLong != null) {
                    onMapTapListener?.invoke(latLong)
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val point = Point(e.x.toDouble(), e.y.toDouble())
                val latLong = mapView.mapViewProjection.fromPixels(point.x, point.y)
                if (latLong != null) {
                    onMapLongClickListener?.invoke(latLong)
                }
            }
        })

        mapView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFingerGestureActive = true
                        activeTwoFingerMode = TwoFingerMode.NONE
                        val dx = (event.getX(1) - event.getX(0)).toDouble()
                        val dy = (event.getY(1) - event.getY(0)).toDouble()
                        initialPointerAngle = Math.toDegrees(kotlin.math.atan2(dy, dx))
                        initialMapRotation = mapView.rotation
                        initialMidY = (event.getY(0) + event.getY(1)) / 2f
                        initialY0 = event.getY(0)
                        initialY1 = event.getY(1)
                        initialTilt = mapTilt

                        if (trackingMode != TrackingMode.FREE && !isTrackingSuspended) {
                            isTrackingSuspended = true
                            onTrackingSuspensionChanged?.invoke(true)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && isTwoFingerGestureActive) {
                        val dx = (event.getX(1) - event.getX(0)).toDouble()
                        val dy = (event.getY(1) - event.getY(0)).toDouble()
                        val currentAngle = Math.toDegrees(kotlin.math.atan2(dy, dx))
                        val angleDiff = Math.abs(currentAngle - initialPointerAngle)

                        val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                        val deltaY = currentMidY - initialMidY
                        val verticalDiff = Math.abs(deltaY)

                        val dy0 = event.getY(0) - initialY0
                        val dy1 = event.getY(1) - initialY1
                        val isParallelVerticalDrag = (dy0 * dy1 > 0) // Both fingers moving in same vertical direction

                        // Classify gesture into exclusive mode
                        if (activeTwoFingerMode == TwoFingerMode.NONE) {
                            if (verticalDiff > 24f && isParallelVerticalDrag && angleDiff < 8.0) {
                                activeTwoFingerMode = TwoFingerMode.TILTING
                            } else if (angleDiff > 6.0) {
                                activeTwoFingerMode = TwoFingerMode.ROTATING
                            }
                        }

                        // Execute exclusively
                        when (activeTwoFingerMode) {
                            TwoFingerMode.ROTATING -> {
                                val angleDelta = (currentAngle - initialPointerAngle).toFloat()
                                mapView.rotation = initialMapRotation + angleDelta
                                updateAllPinRotations()
                                mapView.repaint()
                            }
                            TwoFingerMode.TILTING -> {
                                val newTilt = (initialTilt - deltaY * 0.16f).coerceIn(0f, 55f)
                                mapTilt = newTilt
                                mapView.repaint()
                            }
                            TwoFingerMode.NONE -> {
                                // Waiting for gesture intent
                            }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.pointerCount <= 2) {
                        isTwoFingerGestureActive = false
                        activeTwoFingerMode = TwoFingerMode.NONE
                    }
                }
            }
            false
        }
    }

    fun loadMapFile(file: File): Boolean {
        return try {
            destroyLayers()

            applyThemeBackgroundColor(currentThemePreset)
            mapView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            // Pre-render 75% beyond visible viewport to eliminate white squares during panning
            val overdraw = 1.75
            mapView.model.frameBufferModel.overdrawFactor = overdraw

            // Initialize high-capacity dual-level tile cache (768 RAM tiles = ~200MB pool + 2048 Disk tiles)
            tileCache = AndroidUtil.createTileCache(
                context,
                "mapcache",
                768,
                2048,
                mapView.model.displayModel.tileSize,
                overdraw,
                true
            )

            val newMapDataStore = MapFile(file)
            mapDataStore = newMapDataStore

            val renderTheme = if (currentThemePreset.assetPath != null) {
                try {
                    org.mapsforge.map.android.rendertheme.AssetsRenderTheme(context.assets, "", currentThemePreset.assetPath)
                } catch (e: Exception) {
                    InternalRenderTheme.DEFAULT
                }
            } else {
                InternalRenderTheme.DEFAULT
            }

            tileRendererLayer = AndroidUtil.createTileRendererLayer(
                tileCache,
                mapView.model.mapViewPosition,
                newMapDataStore,
                renderTheme,
                false,
                true,
                true
            )

            mapView.layerManager.layers.add(0, tileRendererLayer)
            isOfflineModeLoaded = true
            mapView.model.mapViewPosition.setCenter(GeoUtils.BAGHDAD_CENTER)
            mapView.model.mapViewPosition.zoomLevel = 14.toByte()
            mapView.repaint()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setMapTheme(preset: MapThemePreset): Boolean {
        currentThemePreset = preset
        applyThemeBackgroundColor(preset)
        val store = mapDataStore ?: return false
        val cache = tileCache ?: return false

        return try {
            val renderTheme = if (preset.assetPath != null) {
                try {
                    org.mapsforge.map.android.rendertheme.AssetsRenderTheme(context.assets, "", preset.assetPath)
                } catch (e: Exception) {
                    InternalRenderTheme.DEFAULT
                }
            } else {
                InternalRenderTheme.DEFAULT
            }

            tileRendererLayer?.let {
                mapView.layerManager.layers.remove(it)
                it.onDestroy()
            }

            tileRendererLayer = AndroidUtil.createTileRendererLayer(
                cache,
                mapView.model.mapViewPosition,
                store,
                renderTheme,
                false,
                true,
                true
            )

            mapView.layerManager.layers.add(0, tileRendererLayer)
            mapView.repaint()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    enum class TrackingMode(val title: String) {
        FREE("Free Pan"),
        FOLLOW("Follow Location"),
        FOLLOW_AND_ROTATE("Follow & Rotate (Navigation)")
    }

    var trackingMode: TrackingMode = TrackingMode.FREE
        private set

    var isTrackingSuspended: Boolean = false
        private set

    var onTrackingSuspensionChanged: ((Boolean) -> Unit)? = null

    private var accuracyCircle: org.mapsforge.map.layer.overlay.Circle? = null
    private var selectionMarker: Marker? = null
    var lastUserLocation: LatLong? = null
        private set
    var lastUserBearing: Float = 0f
        private set

    private var lastStartLocation: LatLong? = null
    private var lastDestLocation: LatLong? = null
    private var lastSelectionLocation: LatLong? = null

    var isCameraAnimating = false
        private set

    private var cameraAnimator: android.animation.ValueAnimator? = null
    private var rotationAnimator: android.animation.ValueAnimator? = null
    private val zoomHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun animateTo(
        target: LatLong,
        targetZoom: Byte? = null,
        durationMs: Long = 420L,
        onComplete: (() -> Unit)? = null
    ) {
        cameraAnimator?.cancel()
        zoomHandler.removeCallbacksAndMessages(null)

        val startCenter = mapView.model.mapViewPosition.center ?: GeoUtils.BAGHDAD_CENTER
        val startLat = startCenter.latitude
        val startLon = startCenter.longitude
        val targetLat = target.latitude
        val targetLon = target.longitude

        val startZoom = mapView.model.mapViewPosition.zoomLevel.toInt()
        val endZoom = targetZoom?.toInt() ?: startZoom

        isCameraAnimating = true

        // Phase 1: Smoothly pan across to target coordinates first (at current zoom level)
        cameraAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val curLat = startLat + (targetLat - startLat) * fraction
                val curLon = startLon + (targetLon - startLon) * fraction
                mapView.model.mapViewPosition.setCenter(LatLong(curLat, curLon))
                mapView.repaint()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    mapView.model.mapViewPosition.setCenter(target)
                    mapView.repaint()

                    // Phase 2: After arriving at target, smoothly zoom into target location (centered on user)
                    if (startZoom != endZoom) {
                        animateZoomSequentially(target, startZoom, endZoom, onComplete)
                    } else {
                        isCameraAnimating = false
                        onComplete?.invoke()
                    }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    isCameraAnimating = false
                }
            })
            start()
        }
    }

    private fun animateZoomSequentially(
        centerTarget: LatLong,
        fromZoom: Int,
        toZoom: Int,
        onComplete: (() -> Unit)?
    ) {
        val zoomSteps = if (toZoom > fromZoom) (fromZoom + 1..toZoom).toList() else (fromZoom - 1 downTo toZoom).toList()
        if (zoomSteps.isEmpty()) {
            isCameraAnimating = false
            onComplete?.invoke()
            return
        }

        var currentStepIdx = 0
        fun runNextZoomStep() {
            if (!isCameraAnimating) return
            if (currentStepIdx < zoomSteps.size) {
                val nextZoom = zoomSteps[currentStepIdx]
                mapView.model.mapViewPosition.setCenter(centerTarget)
                mapView.model.mapViewPosition.zoomLevel = nextZoom.toByte()
                mapView.repaint()
                currentStepIdx++
                zoomHandler.postDelayed({ runNextZoomStep() }, 140L)
            } else {
                mapView.model.mapViewPosition.setCenter(centerTarget)
                mapView.repaint()
                isCameraAnimating = false
                onComplete?.invoke()
            }
        }

        zoomHandler.postDelayed({ runNextZoomStep() }, 60L)
    }

    private fun applyCameraTransform(bearing: Float, animated: Boolean = false) {
        mapView.scaleX = 1.0f
        mapView.scaleY = 1.0f
        mapView.pivotX = mapView.width / 2f
        mapView.pivotY = mapView.height / 2f

        if (animated) {
            val curRot = mapView.rotation
            var diff = (-bearing - curRot) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val targetRot = curRot + diff

            rotationAnimator?.cancel()
            rotationAnimator = android.animation.ValueAnimator.ofFloat(curRot, targetRot).apply {
                duration = 260L
                interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                addUpdateListener { anim ->
                    mapView.rotation = anim.animatedValue as Float
                    updateAllPinRotations()
                    mapView.repaint()
                }
                start()
            }
        } else {
            mapView.rotation = -bearing
            updateAllPinRotations()
        }
    }

    private fun resetCameraTransform(animated: Boolean = true) {
        mapView.scaleX = 1.0f
        mapView.scaleY = 1.0f
        if (animated && mapView.rotation != 0f) {
            val curRot = mapView.rotation
            var diff = (0f - curRot) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val targetRot = curRot + diff

            rotationAnimator?.cancel()
            rotationAnimator = android.animation.ValueAnimator.ofFloat(curRot, targetRot).apply {
                duration = 300L
                interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                addUpdateListener { anim ->
                    mapView.rotation = anim.animatedValue as Float
                    updateAllPinRotations()
                    mapView.repaint()
                }
                start()
            }
        } else {
            mapView.rotation = 0f
            updateAllPinRotations()
        }
    }

    private val arrowBitmapCache = mutableMapOf<Int, Bitmap>()
    private val destPinBitmapCache = mutableMapOf<Int, Bitmap>()
    private val startPinBitmapCache = mutableMapOf<Int, Bitmap>()
    private var lastRenderedPinAngle = -999

    private fun getCachedArrowBitmap(degrees: Float): Bitmap {
        val rounded = ((degrees.toInt() % 360 + 360) % 360) / 4 * 4 // Quantize to 4 deg increments
        return arrowBitmapCache.getOrPut(rounded) {
            createRotatedArrowBitmap(rounded.toFloat())
        }
    }

    private fun getCachedPinBitmap(drawableResId: Int, degrees: Float): Bitmap {
        val rounded = ((degrees.toInt() % 360 + 360) % 360) / 6 * 6 // Quantize to 6 deg increments
        val cache = if (drawableResId == R.drawable.ic_dest_pin) destPinBitmapCache else startPinBitmapCache
        return cache.getOrPut(rounded) {
            createMarkerBitmap(drawableResId, rounded.toFloat(), 36)
        }
    }

    private fun getPinCounterRotation(): Float {
        return if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE && !isTrackingSuspended) {
            lastUserBearing
        } else {
            0f
        }
    }

    private fun updateAllPinRotations() {
        val counterRot = getPinCounterRotation()
        val quantized = ((counterRot.toInt() % 360 + 360) % 360) / 6 * 6
        if (quantized == lastRenderedPinAngle && lastRenderedPinAngle != -999) return
        lastRenderedPinAngle = quantized

        lastSelectionLocation?.let { latLong ->
            val bitmap = getCachedPinBitmap(R.drawable.ic_dest_pin, counterRot)
            if (selectionMarker == null) {
                val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
                selectionMarker = marker
                mapView.layerManager.layers.add(marker)
            } else {
                selectionMarker?.latLong = latLong
                selectionMarker?.bitmap = bitmap
            }
        }

        lastStartLocation?.let { latLong ->
            val bitmap = getCachedPinBitmap(R.drawable.ic_start_pin, counterRot)
            if (startMarker == null) {
                val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
                startMarker = marker
                mapView.layerManager.layers.add(marker)
            } else {
                startMarker?.latLong = latLong
                startMarker?.bitmap = bitmap
            }
        }

        lastDestLocation?.let { latLong ->
            val bitmap = getCachedPinBitmap(R.drawable.ic_dest_pin, counterRot)
            if (destMarker == null) {
                val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
                destMarker = marker
                mapView.layerManager.layers.add(marker)
            } else {
                destMarker?.latLong = latLong
                destMarker?.bitmap = bitmap
            }
        }
    }

    fun setTrackingMode(mode: TrackingMode, animated: Boolean = true) {
        trackingMode = mode
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)

        if (mode == TrackingMode.FREE) {
            resetCameraTransform(animated = animated)
        } else if (mode == TrackingMode.FOLLOW && lastUserLocation != null) {
            resetCameraTransform(animated = animated)
            if (animated) {
                animateTo(lastUserLocation!!, 16.toByte(), durationMs = 520L)
            } else {
                centerOn(lastUserLocation!!, 16.toByte())
            }
        } else if (mode == TrackingMode.FOLLOW_AND_ROTATE && lastUserLocation != null) {
            if (animated) {
                animateTo(lastUserLocation!!, 16.toByte(), durationMs = 520L)
            } else {
                centerOn(lastUserLocation!!, 16.toByte())
            }
            applyCameraTransform(lastUserBearing, animated = animated)
        }
        setUserLocation(lastUserLocation)
    }

    fun resumeTracking(animated: Boolean = true) {
        if (trackingMode == TrackingMode.FREE) return
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)

        val loc = lastUserLocation ?: return
        if (animated) {
            animateTo(loc, 16.toByte(), durationMs = 520L)
        } else {
            centerOn(loc, 16.toByte())
        }
        if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE) {
            applyCameraTransform(lastUserBearing, animated = animated)
        } else {
            resetCameraTransform(animated = animated)
        }
        mapView.repaint()
    }

    fun setUserBearing(bearingDegrees: Float) {
        lastUserBearing = bearingDegrees
        if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE && !isTrackingSuspended) {
            mapView.rotation = -bearingDegrees
            updateAllPinRotations()
            userLocationMarker?.bitmap = getCachedArrowBitmap(0f)
        } else {
            userLocationMarker?.bitmap = getCachedArrowBitmap(bearingDegrees)
        }
        mapView.repaint()
    }

    fun setUserLocation(
        latLong: LatLong?,
        accuracyMeters: Float = 0f,
        bearingDegrees: Float? = null
    ) {
        lastUserLocation = latLong
        if (bearingDegrees != null) {
            lastUserBearing = bearingDegrees
        }
        if (latLong == null) {
            userLocationMarker?.let { mapView.layerManager.layers.remove(it) }
            accuracyCircle?.let { mapView.layerManager.layers.remove(it) }
            userLocationMarker = null
            accuracyCircle = null
            mapView.repaint()
            return
        }

        // 1. Accuracy Circle (soft blue fill with stroke)
        if (accuracyMeters > 5f) {
            if (accuracyCircle == null) {
                val fillPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = AndroidGraphicFactory.INSTANCE.createColor(35, 30, 136, 229)
                    setStyle(Style.FILL)
                }
                val strokePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = AndroidGraphicFactory.INSTANCE.createColor(120, 30, 136, 229)
                    strokeWidth = 2f
                    setStyle(Style.STROKE)
                }
                val circle = org.mapsforge.map.layer.overlay.Circle(latLong, accuracyMeters.coerceAtMost(150f), fillPaint, strokePaint)
                accuracyCircle = circle
                mapView.layerManager.layers.add(circle)
            } else {
                accuracyCircle?.setLatLong(latLong)
                accuracyCircle?.radius = accuracyMeters.coerceAtMost(150f)
            }
        } else {
            accuracyCircle?.let { mapView.layerManager.layers.remove(it) }
            accuracyCircle = null
        }

        // 2. Directional Navigation Puck (Zero Allocation via Cache)
        val puckRotation = if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE && !isTrackingSuspended) 0f else lastUserBearing
        val arrowBitmap = getCachedArrowBitmap(puckRotation)
        if (userLocationMarker == null) {
            val marker = Marker(latLong, arrowBitmap, 0, 0)
            userLocationMarker = marker
            mapView.layerManager.layers.add(marker)
        } else {
            userLocationMarker?.latLong = latLong
            userLocationMarker?.bitmap = arrowBitmap
        }

        // 3. Camera Position (Only center on GPS location change if tracking is active, not suspended, and not animating)
        if (!isTrackingSuspended && trackingMode != TrackingMode.FREE && !isCameraAnimating) {
            mapView.model.mapViewPosition.setCenter(latLong)
        }

        mapView.repaint()
    }

    fun setSelectionPoint(latLong: LatLong?) {
        lastSelectionLocation = latLong
        selectionMarker?.let { mapView.layerManager.layers.remove(it) }
        selectionMarker = null

        if (latLong != null) {
            val bitmap = createMarkerBitmap(R.drawable.ic_dest_pin, getPinCounterRotation(), 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            selectionMarker = marker
            mapView.layerManager.layers.add(marker)
        }
        mapView.repaint()
    }

    fun clearSelectionPoint() {
        lastSelectionLocation = null
        setSelectionPoint(null)
    }

    private fun createRotatedArrowBitmap(degrees: Float): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_navigation_arrow)!!
        val density = context.resources.displayMetrics.density
        val px = (38 * density).toInt()
        val androidBitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(androidBitmap)
        canvas.rotate(degrees, px / 2f, px / 2f)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        val bitmapDrawable = android.graphics.drawable.BitmapDrawable(context.resources, androidBitmap)
        return AndroidGraphicFactory.convertToBitmap(bitmapDrawable)
    }

    fun setStartPoint(latLong: LatLong?) {
        lastStartLocation = latLong
        startMarker?.let { mapView.layerManager.layers.remove(it) }
        startMarker = null

        if (latLong != null) {
            val bitmap = createMarkerBitmap(R.drawable.ic_start_pin, getPinCounterRotation(), 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            startMarker = marker
            mapView.layerManager.layers.add(marker)
        }
        mapView.repaint()
    }

    fun setDestinationPoint(latLong: LatLong?) {
        lastDestLocation = latLong
        destMarker?.let { mapView.layerManager.layers.remove(it) }
        destMarker = null

        if (latLong != null) {
            val bitmap = createMarkerBitmap(R.drawable.ic_dest_pin, getPinCounterRotation(), 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            destMarker = marker
            mapView.layerManager.layers.add(marker)
        }
        mapView.repaint()
    }

    fun displayRoute(points: List<LatLong>, boundingBox: BoundingBox?) {
        clearRoute()

        if (points.size < 2) return

        // Create outline polyline (darker blue border)
        val outlinePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = AndroidGraphicFactory.INSTANCE.createColor(255, 13, 71, 161)
            strokeWidth = 14f
            setStyle(Style.STROKE)
        }
        val outline = Polyline(outlinePaint, AndroidGraphicFactory.INSTANCE)
        outline.addPoints(points)
        routeOutlinePolyline = outline
        mapView.layerManager.layers.add(outline)

        // Create inner polyline (vibrant blue)
        val routePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = AndroidGraphicFactory.INSTANCE.createColor(255, 30, 136, 229)
            strokeWidth = 10f
            setStyle(Style.STROKE)
        }
        val polyline = Polyline(routePaint, AndroidGraphicFactory.INSTANCE)
        polyline.addPoints(points)
        routePolyline = polyline
        mapView.layerManager.layers.add(polyline)

        if (boundingBox != null) {
            mapView.model.mapViewPosition.setCenter(boundingBox.centerPoint)
        }

        mapView.repaint()
        preloadRouteTiles(points)
    }

    private fun preloadRouteTiles(points: List<LatLong>) {
        if (points.isEmpty() || !isOfflineModeLoaded) return

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                val sampledPoints = ArrayList<LatLong>()
                var lastAdded = points.first()
                sampledPoints.add(lastAdded)

                for (pt in points) {
                    if (GeoUtils.calculateDistance(lastAdded, pt) >= 300.0) {
                        sampledPoints.add(pt)
                        lastAdded = pt
                    }
                }
                if (sampledPoints.last() != points.last()) {
                    sampledPoints.add(points.last())
                }

                // Pre-warm tiles into cache for navigation driving zoom levels
                val zoomLevels = listOf(14.toByte(), 15.toByte(), 16.toByte())
                for (zoom in zoomLevels) {
                    for (pt in sampledPoints) {
                        val tileX = org.mapsforge.core.util.MercatorProjection.longitudeToTileX(pt.longitude, zoom)
                        val tileY = org.mapsforge.core.util.MercatorProjection.latitudeToTileY(pt.latitude, zoom)
                        val tile = org.mapsforge.core.model.Tile(tileX, tileY, zoom, 256)
                        tileCache?.containsKey(Job(tile, false))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun clearRoute() {
        routePolyline?.let { mapView.layerManager.layers.remove(it) }
        routeOutlinePolyline?.let { mapView.layerManager.layers.remove(it) }
        routePolyline = null
        routeOutlinePolyline = null
        mapView.repaint()
    }

    fun clearAllMarkersAndRoute() {
        setStartPoint(null)
        setDestinationPoint(null)
        clearRoute()
    }

    fun centerOn(latLong: LatLong, zoomLevel: Byte = 14.toByte()) {
        mapView.model.mapViewPosition.setCenter(latLong)
        mapView.model.mapViewPosition.zoomLevel = zoomLevel
        mapView.repaint()
    }

    private fun createMarkerBitmap(drawableResId: Int, counterRotation: Float = 0f, sizeDp: Int = 36): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableResId)!!
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).toInt()
        val androidBitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(androidBitmap)
        if (counterRotation != 0f) {
            canvas.rotate(counterRotation, px / 2f, px / 2f)
        }
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        val bitmapDrawable = android.graphics.drawable.BitmapDrawable(context.resources, androidBitmap)
        return AndroidGraphicFactory.convertToBitmap(bitmapDrawable)
    }

    private fun destroyLayers() {
        tileDownloadLayer?.let {
            it.onPause()
            it.onDestroy()
            mapView.layerManager.layers.remove(it)
        }
        tileDownloadLayer = null

        tileRendererLayer?.let {
            mapView.layerManager.layers.remove(it)
            it.onDestroy()
        }
        tileRendererLayer = null

        tileCache?.destroy()
        tileCache = null

        mapDataStore?.close()
        mapDataStore = null

        isOfflineModeLoaded = false
    }

    fun onResume() {
        tileDownloadLayer?.onResume()
    }

    fun onPause() {
        tileDownloadLayer?.onPause()
    }

    fun onDestroy() {
        destroyLayers()
        mapView.destroy()
    }
}
