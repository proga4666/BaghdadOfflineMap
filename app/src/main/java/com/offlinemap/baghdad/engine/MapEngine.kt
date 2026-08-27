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
            false
        }
    }

    fun loadMapFile(file: File): Boolean {
        return try {
            destroyLayers()

            applyThemeBackgroundColor(currentThemePreset)

            // Pre-render 60% beyond visible viewport to eliminate white squares during panning
            val overdraw = 1.6
            mapView.model.frameBufferModel.overdrawFactor = overdraw

            // Initialize high-capacity dual-level tile cache (256 RAM tiles + 1024 Disk tiles)
            tileCache = AndroidUtil.createTileCache(
                context,
                "mapcache",
                256,
                1024,
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

    private fun applyCameraTransform(bearing: Float) {
        mapView.scaleX = 1.0f
        mapView.scaleY = 1.0f
        mapView.pivotX = mapView.width / 2f
        mapView.pivotY = mapView.height / 2f
        mapView.rotation = -bearing
        updateAllPinRotations()
    }

    private fun resetCameraTransform() {
        mapView.rotation = 0f
        mapView.scaleX = 1.0f
        mapView.scaleY = 1.0f
        updateAllPinRotations()
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

        lastSelectionLocation?.let { latLong ->
            selectionMarker?.let { mapView.layerManager.layers.remove(it) }
            val bitmap = createMarkerBitmap(R.drawable.ic_dest_pin, counterRot, 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            selectionMarker = marker
            mapView.layerManager.layers.add(marker)
        }

        lastStartLocation?.let { latLong ->
            startMarker?.let { mapView.layerManager.layers.remove(it) }
            val bitmap = createMarkerBitmap(R.drawable.ic_start_pin, counterRot, 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            startMarker = marker
            mapView.layerManager.layers.add(marker)
        }

        lastDestLocation?.let { latLong ->
            destMarker?.let { mapView.layerManager.layers.remove(it) }
            val bitmap = createMarkerBitmap(R.drawable.ic_dest_pin, counterRot, 36)
            val marker = Marker(latLong, bitmap, 0, -bitmap.height / 2)
            destMarker = marker
            mapView.layerManager.layers.add(marker)
        }
    }

    fun setTrackingMode(mode: TrackingMode) {
        trackingMode = mode
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)

        if (mode == TrackingMode.FREE) {
            resetCameraTransform()
        } else if (mode == TrackingMode.FOLLOW && lastUserLocation != null) {
            resetCameraTransform()
            mapView.model.mapViewPosition.setCenter(lastUserLocation)
        } else if (mode == TrackingMode.FOLLOW_AND_ROTATE && lastUserLocation != null) {
            mapView.model.mapViewPosition.setCenter(lastUserLocation)
            applyCameraTransform(lastUserBearing)
        }
        updateUserLocationVisuals()
    }

    fun resumeTracking() {
        if (trackingMode == TrackingMode.FREE) return
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)

        val loc = lastUserLocation ?: return
        mapView.model.mapViewPosition.setCenter(loc)
        if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE) {
            applyCameraTransform(lastUserBearing)
        } else {
            resetCameraTransform()
        }
        mapView.repaint()
    }

    fun setUserLocation(
        latLong: LatLong?,
        accuracyMeters: Float = 0f,
        bearingDegrees: Float = 0f
    ) {
        lastUserLocation = latLong
        lastUserBearing = bearingDegrees
        updateUserLocationVisuals(accuracyMeters)
    }

    private fun updateUserLocationVisuals(accuracyMeters: Float = 20f) {
        val latLong = lastUserLocation
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

        // 2. Directional Navigation Puck
        userLocationMarker?.let { mapView.layerManager.layers.remove(it) }
        val puckRotation = if (trackingMode == TrackingMode.FOLLOW_AND_ROTATE && !isTrackingSuspended) 0f else lastUserBearing
        val arrowBitmap = createRotatedArrowBitmap(puckRotation)
        val marker = Marker(latLong, arrowBitmap, 0, 0)
        userLocationMarker = marker
        mapView.layerManager.layers.add(marker)

        // 3. Camera behavior (Only lock camera if user is NOT currently panning)
        if (!isTrackingSuspended) {
            when (trackingMode) {
                TrackingMode.FOLLOW -> {
                    mapView.model.mapViewPosition.setCenter(latLong)
                    resetCameraTransform()
                }
                TrackingMode.FOLLOW_AND_ROTATE -> {
                    mapView.model.mapViewPosition.setCenter(latLong)
                    applyCameraTransform(lastUserBearing)
                }
                TrackingMode.FREE -> {}
            }
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
