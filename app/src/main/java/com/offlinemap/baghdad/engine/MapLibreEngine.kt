package com.offlinemap.baghdad.engine

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.offlinemap.baghdad.R
import com.offlinemap.baghdad.utils.GeoUtils
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import android.location.Location
import android.location.LocationManager
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.mapsforge.core.model.LatLong

class MapLibreEngine(
    private val context: Context,
    val mapView: MapView
) {

    var mapLibreMap: MapLibreMap? = null
        private set

    var onMapTapListener: ((LatLong) -> Unit)? = null
    var onMapLongClickListener: ((LatLong) -> Unit)? = null
    var onTrackingSuspensionChanged: ((Boolean) -> Unit)? = null

    var trackingMode: MapEngine.TrackingMode = MapEngine.TrackingMode.FREE
        private set
    var isTrackingSuspended: Boolean = false
        private set

    var lastUserLocation: LatLong? = null
        private set
    var lastUserBearing: Float = 0f
        private set

    private var startPointLatLong: LatLong? = null
    private var destPointLatLong: LatLong? = null
    private var selectionPointLatLong: LatLong? = null

    // GeoJSON Sources
    private var routeSource: GeoJsonSource? = null
    private var markerSource: GeoJsonSource? = null
    private var userLocationSource: GeoJsonSource? = null

    var currentThemePreset: MapThemePreset = MapThemePreset.MODERN_LIGHT
        private set

    private var activeRoutePoints: List<LatLong>? = null

    companion object {
        const val ROUTE_SOURCE_ID = "baghdad_route_source"
        const val ROUTE_CASING_LAYER_ID = "baghdad_route_casing_layer"
        const val ROUTE_LAYER_ID = "baghdad_route_layer"

        const val TRAVELED_ROUTE_SOURCE_ID = "baghdad_traveled_route_source"
        const val TRAVELED_ROUTE_LAYER_ID = "baghdad_traveled_route_layer"

        const val MARKERS_SOURCE_ID = "baghdad_markers_source"
        const val MARKERS_LAYER_ID = "baghdad_markers_layer"

        const val USER_LOC_SOURCE_ID = "baghdad_user_loc_source"
        const val USER_LOC_LAYER_ID = "baghdad_user_loc_layer"

        const val ICON_START = "icon_start_pin"
        const val ICON_DEST = "icon_dest_pin"
        const val ICON_NAV_ARROW = "icon_nav_arrow"
    }

    private var traveledRouteSource: GeoJsonSource? = null

    fun getStyleUriForPreset(preset: MapThemePreset): String {
        return when (preset) {
            MapThemePreset.WAZE_DARK, MapThemePreset.MIDNIGHT_DARK -> "https://tiles.openfreemap.org/styles/dark"
            MapThemePreset.WAZE_LIGHT, MapThemePreset.MODERN_LIGHT -> "https://tiles.openfreemap.org/styles/bright"
            MapThemePreset.OSM_CLASSIC -> "https://tiles.openfreemap.org/styles/liberty"
        }
    }

    fun initialize(savedInstanceState: Bundle?, onReady: () -> Unit) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            setupMap(map, onReady)
        }
    }

    private fun setupMap(map: MapLibreMap, onReady: () -> Unit) {
        val styleUri = getStyleUriForPreset(currentThemePreset)
        map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
            setupIcons(style)
            setupRouteLayers(style)
            setupMarkerLayers(style)
            setupUserLocationLayer(style)

            // Initial view on Baghdad Center
            val initialPos = CameraPosition.Builder()
                .target(LatLng(GeoUtils.BAGHDAD_CENTER.latitude, GeoUtils.BAGHDAD_CENTER.longitude))
                .zoom(13.5)
                .tilt(0.0)
                .build()
            map.cameraPosition = initialPos

            // Click listeners
            map.addOnMapClickListener { latLng ->
                val ll = LatLong(latLng.latitude, latLng.longitude)
                onMapTapListener?.invoke(ll)
                true
            }

            map.addOnMapLongClickListener { latLng ->
                val ll = LatLong(latLng.latitude, latLng.longitude)
                onMapLongClickListener?.invoke(ll)
                true
            }

            // Camera move listener (detect user manual drag to suspend tracking)
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    if (trackingMode != MapEngine.TrackingMode.FREE && !isTrackingSuspended) {
                        isTrackingSuspended = true
                        onTrackingSuspensionChanged?.invoke(true)
                    }
                }
            }

            onReady()
        }
    }

    private fun setupIcons(style: Style) {
        val startDrawable = ContextCompat.getDrawable(context, R.drawable.ic_start_pin)
        val destDrawable = ContextCompat.getDrawable(context, R.drawable.ic_dest_pin)
        val arrowDrawable = ContextCompat.getDrawable(context, R.drawable.ic_navigation_arrow)

        startDrawable?.let { style.addImage(ICON_START, it) }
        destDrawable?.let { style.addImage(ICON_DEST, it) }
        arrowDrawable?.let { style.addImage(ICON_NAV_ARROW, it) }
    }

    private fun setupRouteLayers(style: Style) {
        // 1. Traveled route layer (light gray behind user)
        val tSource = GeoJsonSource(TRAVELED_ROUTE_SOURCE_ID)
        style.addSource(tSource)
        traveledRouteSource = tSource

        val traveledLayer = LineLayer(TRAVELED_ROUTE_LAYER_ID, TRAVELED_ROUTE_SOURCE_ID).apply {
            setProperties(
                lineColor(Color.parseColor("#90A4AE")),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.55f)
            )
        }
        style.addLayer(traveledLayer)

        // 2. Active remaining route layers (vibrant blue ahead)
        val rSource = GeoJsonSource(ROUTE_SOURCE_ID)
        style.addSource(rSource)
        routeSource = rSource

        val casingLayer = LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).apply {
            setProperties(
                lineColor(Color.parseColor("#0D47A1")),
                lineWidth(9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(casingLayer)

        val mainLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
            setProperties(
                lineColor(Color.parseColor("#00E5FF")),
                lineWidth(6.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayerAbove(mainLayer, ROUTE_CASING_LAYER_ID)
    }

    private fun setupMarkerLayers(style: Style) {
        val mSource = GeoJsonSource(MARKERS_SOURCE_ID)
        style.addSource(mSource)
        markerSource = mSource

        val symbolLayer = SymbolLayer(MARKERS_LAYER_ID, MARKERS_SOURCE_ID).apply {
            setProperties(
                iconImage("{type}"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM)
            )
        }
        style.addLayer(symbolLayer)
    }

    private var locationComponent: LocationComponent? = null

    private fun setupUserLocationLayer(style: Style) {
        val map = mapLibreMap ?: return
        try {
            val options = LocationComponentOptions.builder(context)
                .pulseEnabled(true)
                .pulseColor(Color.parseColor("#00E5FF"))
                .pulseAlpha(0.35f)
                .foregroundDrawable(R.drawable.ic_navigation_arrow)
                .bearingTintColor(Color.parseColor("#00E5FF"))
                .accuracyAlpha(0.18f)
                .accuracyColor(Color.parseColor("#29B6F6"))
                .elevation(8f)
                .build()

            val activationOptions = LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build()

            val lc = map.locationComponent
            lc.activateLocationComponent(activationOptions)
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.COMPASS
            lc.cameraMode = CameraMode.NONE
            locationComponent = lc
        } catch (e: Exception) {
            Log.e("MapLibreEngine", "Failed to activate LocationComponent: ${e.message}")
        }
    }

    fun setUserLocation(latLong: LatLong, accuracy: Float = 4f, bearing: Float = 0f) {
        lastUserLocation = latLong
        lastUserBearing = bearing

        // 1. Update native hardware LocationComponent (zero flicker, zero disappear on tilt)
        val androidLoc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = latLong.latitude
            longitude = latLong.longitude
            this.accuracy = accuracy
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        locationComponent?.forceLocationUpdate(androidLoc)

        // 2. Update live route trimming
        if (activeRoutePoints != null) {
            updateRouteProgress(latLong)
        }

        // 3. Camera follow & 3D tilt tracking
        val map = mapLibreMap ?: return
        if (!isTrackingSuspended) {
            when (trackingMode) {
                MapEngine.TrackingMode.FOLLOW -> {
                    val camera = CameraPosition.Builder()
                        .target(LatLng(latLong.latitude, latLong.longitude))
                        .build()
                    map.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 350)
                }
                MapEngine.TrackingMode.FOLLOW_AND_ROTATE -> {
                    val camera = CameraPosition.Builder()
                        .target(LatLng(latLong.latitude, latLong.longitude))
                        .bearing(bearing.toDouble())
                        .tilt(48.0)
                        .zoom(16.8)
                        .build()
                    map.easeCamera(CameraUpdateFactory.newCameraPosition(camera), 350)
                }
                MapEngine.TrackingMode.FREE -> {}
            }
        }
    }

    fun updateRouteProgress(userLoc: LatLong) {
        val points = activeRoutePoints ?: return
        if (points.size < 2) return

        // 1. If user is off-route (> 35m from polyline), do NOT create an artificial straight-line shortcut
        val minPolyDistance = GeoUtils.minDistanceToPolyline(userLoc, points)
        if (minPolyDistance > 35.0) {
            return
        }

        // 2. Find closest line segment on the active route
        var closestIdx = 0
        var minSegDist = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = GeoUtils.distanceToSegment(userLoc, points[i], points[i + 1])
            if (d < minSegDist) {
                minSegDist = d
                closestIdx = i
            }
        }

        // 3. Project user position onto that exact road segment (strictly follows the road geometry)
        val a = points[closestIdx]
        val b = points[closestIdx + 1]
        val dx = b.longitude - a.longitude
        val dy = b.latitude - a.latitude
        val segLenSq = dx * dx + dy * dy
        val t = if (segLenSq > 0) {
            (((userLoc.longitude - a.longitude) * dx + (userLoc.latitude - a.latitude) * dy) / segLenSq).coerceIn(0.0, 1.0)
        } else 0.0
        val projectedPoint = Point.fromLngLat(a.longitude + t * dx, a.latitude + t * dy)

        val traveledPoints = mutableListOf<Point>()
        for (i in 0..closestIdx) {
            traveledPoints.add(Point.fromLngLat(points[i].longitude, points[i].latitude))
        }
        traveledPoints.add(projectedPoint)

        val remainingPoints = mutableListOf<Point>()
        remainingPoints.add(projectedPoint)
        for (i in (closestIdx + 1) until points.size) {
            remainingPoints.add(Point.fromLngLat(points[i].longitude, points[i].latitude))
        }

        if (traveledPoints.size >= 2) {
            val traveledLine = Feature.fromGeometry(LineString.fromLngLats(traveledPoints))
            traveledRouteSource?.setGeoJson(FeatureCollection.fromFeatures(listOf(traveledLine)))
        }

        if (remainingPoints.size >= 2) {
            val remainingLine = Feature.fromGeometry(LineString.fromLngLats(remainingPoints))
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(listOf(remainingLine)))
        }
    }

    fun setTrackingMode(mode: MapEngine.TrackingMode, animated: Boolean = true) {
        trackingMode = mode
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)

        val map = mapLibreMap ?: return
        val loc = lastUserLocation ?: GeoUtils.BAGHDAD_CENTER

        when (mode) {
            MapEngine.TrackingMode.FREE -> {
                val pos = CameraPosition.Builder()
                    .tilt(0.0)
                    .bearing(0.0)
                    .build()
                if (animated) map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 400)
                else map.cameraPosition = pos
            }
            MapEngine.TrackingMode.FOLLOW -> {
                val pos = CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .tilt(0.0)
                    .bearing(0.0)
                    .zoom(15.5)
                    .build()
                if (animated) map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500)
                else map.cameraPosition = pos
            }
            MapEngine.TrackingMode.FOLLOW_AND_ROTATE -> {
                val pos = CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .tilt(48.0)
                    .bearing(lastUserBearing.toDouble())
                    .zoom(16.8)
                    .build()
                if (animated) map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500)
                else map.cameraPosition = pos
            }
        }
    }

    fun resumeTracking(animated: Boolean = true) {
        isTrackingSuspended = false
        onTrackingSuspensionChanged?.invoke(false)
        setTrackingMode(if (trackingMode == MapEngine.TrackingMode.FREE) MapEngine.TrackingMode.FOLLOW else trackingMode, animated)
    }

    fun setStartPoint(latLong: LatLong?) {
        startPointLatLong = latLong
        updateMarkers()
    }

    fun setDestinationPoint(latLong: LatLong?) {
        destPointLatLong = latLong
        updateMarkers()
    }

    fun setSelectionPoint(latLong: LatLong?) {
        selectionPointLatLong = latLong
        updateMarkers()
    }

    fun clearSelectionPoint() {
        selectionPointLatLong = null
        updateMarkers()
    }

    private fun updateMarkers() {
        val features = mutableListOf<Feature>()

        // Delete green marker on current location: Only show start marker if not at live user location
        startPointLatLong?.let { start ->
            val userLoc = lastUserLocation
            val isAtUserLoc = (userLoc != null && GeoUtils.calculateDistance(userLoc, start) < 35.0)
            if (!isAtUserLoc) {
                val f = Feature.fromGeometry(Point.fromLngLat(start.longitude, start.latitude))
                f.addStringProperty("type", ICON_START)
                features.add(f)
            }
        }

        destPointLatLong?.let {
            val f = Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
            f.addStringProperty("type", ICON_DEST)
            features.add(f)
        }

        selectionPointLatLong?.let {
            val f = Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
            f.addStringProperty("type", ICON_DEST)
            features.add(f)
        }

        markerSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setMapTheme(preset: MapThemePreset) {
        currentThemePreset = preset
        val map = mapLibreMap ?: return
        val styleUri = getStyleUriForPreset(preset)
        map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
            setupIcons(style)
            setupRouteLayers(style)
            setupMarkerLayers(style)
            setupUserLocationLayer(style)
            updateMarkers()
            activeRoutePoints?.let { pts ->
                displayRoute(pts)
            }
            lastUserLocation?.let { loc ->
                setUserLocation(loc, 0f, lastUserBearing)
            }
        }
    }

    fun displayRoute(points: List<LatLong>, boundingBox: org.mapsforge.core.model.BoundingBox? = null, fitCamera: Boolean = true) {
        activeRoutePoints = points
        if (points.size < 2) {
            clearRoute()
            return
        }

        traveledRouteSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))

        val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val lineString = LineString.fromLngLats(coords)
        val feature = Feature.fromGeometry(lineString)
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))

        // Fit camera to route bounds smoothly only when requested & not following GPS
        val map = mapLibreMap ?: return
        if (fitCamera && trackingMode == MapEngine.TrackingMode.FREE && points.size >= 2) {
            val boundsBuilder = LatLngBounds.Builder()
            for (p in points) {
                boundsBuilder.include(LatLng(p.latitude, p.longitude))
            }
            val bounds = boundsBuilder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 800)
        }
    }

    fun clearRoute() {
        activeRoutePoints = null
        traveledRouteSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    fun clearAllMarkersAndRoute() {
        startPointLatLong = null
        destPointLatLong = null
        selectionPointLatLong = null
        updateMarkers()
        clearRoute()
    }

    fun animateTo(latLong: LatLong, zoom: Double = 15.0, durationMs: Long = 500L) {
        val map = mapLibreMap ?: return
        val pos = CameraPosition.Builder()
            .target(LatLng(latLong.latitude, latLong.longitude))
            .zoom(zoom)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), durationMs.toInt())
    }

    fun centerOn(latLong: LatLong, zoom: Double = 14.0) {
        val map = mapLibreMap ?: return
        val pos = CameraPosition.Builder()
            .target(LatLng(latLong.latitude, latLong.longitude))
            .zoom(zoom)
            .build()
        map.cameraPosition = pos
    }

    // Lifecycle delegates
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)
    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() = mapView.onDestroy()
}
