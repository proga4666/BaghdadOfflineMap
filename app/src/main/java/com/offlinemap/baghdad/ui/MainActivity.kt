package com.offlinemap.baghdad.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.offlinemap.baghdad.R
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.data.repository.PlaceSearchRepository
import com.offlinemap.baghdad.data.repository.SearchHistoryRepository
import com.offlinemap.baghdad.data.repository.SearchPlace
import com.offlinemap.baghdad.databinding.ActivityMainBinding
import com.offlinemap.baghdad.engine.CompassSensorManager
import com.offlinemap.baghdad.engine.MapEngine
import com.offlinemap.baghdad.engine.MapThemePreset
import com.offlinemap.baghdad.ui.adapter.InstructionAdapter
import com.offlinemap.baghdad.ui.adapter.PlaceSearchAdapter
import com.offlinemap.baghdad.ui.dialogs.LocationPickerSheet
import com.offlinemap.baghdad.ui.dialogs.UnifiedSettingsDialog
import com.offlinemap.baghdad.ui.viewmodel.MapViewModel
import com.offlinemap.baghdad.ui.viewmodel.RouteState
import com.offlinemap.baghdad.utils.GeoUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MapViewModel by viewModels()

    private lateinit var mapView: MapView
    private lateinit var mapEngine: MapEngine
    private lateinit var instructionAdapter: InstructionAdapter
    private lateinit var searchAdapter: PlaceSearchAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val searchRepo = PlaceSearchRepository()
    private lateinit var searchHistoryRepo: SearchHistoryRepository
    private lateinit var compassManager: CompassSensorManager

    private var lastGpsLocation: Location? = null
    private var selectedPinLocation: LatLong? = null
    private var isStartPointDynamicGps: Boolean = true
    private var autoRecenterJob: kotlinx.coroutines.Job? = null
    private var lastCalculatedStartLocation: LatLong? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Centered on Baghdad Center", Toast.LENGTH_SHORT).show()
            mapEngine.centerOn(GeoUtils.BAGHDAD_CENTER, 14.toByte())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        searchHistoryRepo = SearchHistoryRepository(this)

        setupMapView()
        setupCompassAndLocation()
        setupSearch()
        setupUI()
        setupObservers()

        val existingMap = viewModel.repository.findPrimaryMapFile()
        if (existingMap != null && existingMap.exists()) {
            mapEngine.loadMapFile(existingMap)
        }
    }

    private fun View.showAnimated(duration: Long = 260L) {
        if (this.visibility == View.VISIBLE && this.alpha == 1f) return
        this.clearAnimation()
        this.visibility = View.VISIBLE
        this.alpha = 0f
        this.translationY = 25f
        this.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    private fun View.hideAnimated(duration: Long = 200L) {
        if (this.visibility == View.GONE) return
        this.clearAnimation()
        this.animate()
            .alpha(0f)
            .translationY(25f)
            .setDuration(duration)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .withEndAction {
                this.visibility = View.GONE
            }
            .start()
    }

    private fun setupMapView() {
        mapView = MapView(this)
        val metrics = resources.displayMetrics
        val diagonal = kotlin.math.hypot(metrics.widthPixels.toDouble(), metrics.heightPixels.toDouble()).toInt()
        val size = diagonal + 200
        val lp = android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.CENTER
        }
        binding.mapContainer.addView(mapView, lp)
        mapEngine = MapEngine(this, mapView)

        val savedThemeName = getSharedPreferences("baghdad_map_prefs", Context.MODE_PRIVATE)
            .getString("map_theme_preset", MapThemePreset.WAZE_DARK.name)
        val preset = try {
            MapThemePreset.valueOf(savedThemeName ?: "")
        } catch (e: Exception) {
            MapThemePreset.WAZE_DARK
        }
        mapEngine.setMapTheme(preset)

        // 1. Single Tap on Map: Drop temporary marker & show Options Callout ("Set as Start" / "Set as Destination")
        mapEngine.onMapTapListener = { latLong ->
            if (binding.cardSearchResults.visibility == View.VISIBLE) {
                hideSearchDropdown()
            } else {
                selectedPinLocation = latLong
                mapEngine.setSelectionPoint(latLong)

                val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation ?: GeoUtils.BAGHDAD_CENTER
                val distance = GeoUtils.calculateDistance(userLoc, latLong)

                binding.tvPinLocationTitle.text = "Selected Location (موقع محدد)"
                binding.tvPinLocationSubtitle.text = String.format(
                    Locale.getDefault(),
                    "%.4f, %.4f • %s away",
                    latLong.latitude,
                    latLong.longitude,
                    GeoUtils.formatDistance(distance)
                )
                binding.cardPinSelectionCallout.showAnimated()
            }
        }

        mapEngine.onMapLongClickListener = { latLong ->
            selectedPinLocation = latLong
            mapEngine.setSelectionPoint(latLong)

            val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation ?: GeoUtils.BAGHDAD_CENTER
            val distance = GeoUtils.calculateDistance(userLoc, latLong)

            binding.tvPinLocationTitle.text = "Selected Location (موقع محدد)"
            binding.tvPinLocationSubtitle.text = String.format(
                Locale.getDefault(),
                "%.4f, %.4f • %s away",
                latLong.latitude,
                latLong.longitude,
                GeoUtils.formatDistance(distance)
            )
            binding.cardPinSelectionCallout.showAnimated()
        }

        // 2. Handle Camera Pan Interruption: Show/hide floating Re-center button (No auto-steal timer!)
        mapEngine.onTrackingSuspensionChanged = { isSuspended ->
            if (isSuspended) {
                binding.btnRecenterFloating.showAnimated()
            } else {
                binding.btnRecenterFloating.hideAnimated()
            }
        }
    }

    private fun setupCompassAndLocation() {
        compassManager = CompassSensorManager(this)
        compassManager.onAzimuthChanged = { azimuth ->
            mapEngine.setUserBearing(azimuth)
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun setupSearch() {
        searchAdapter = PlaceSearchAdapter { place ->
            onPlaceSelected(place)
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter

        fun showSearchHistoryOrSuggestions(query: String = "") {
            val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation ?: GeoUtils.BAGHDAD_CENTER
            if (query.trim().isEmpty()) {
                val recents = searchHistoryRepo.getRecentSearches(userLoc)
                if (recents.isNotEmpty()) {
                    binding.layoutSearchHistoryHeader.visibility = View.VISIBLE
                    binding.tvSearchDropdownTitle.text = "🕒 Recent Searches (عمليات البحث الأخيرة)"
                    binding.btnClearSearchHistory.visibility = View.VISIBLE
                    searchAdapter.updatePlaces(recents)
                } else {
                    binding.layoutSearchHistoryHeader.visibility = View.VISIBLE
                    binding.tvSearchDropdownTitle.text = "⭐ Top Suggested Baghdad Destinations"
                    binding.btnClearSearchHistory.visibility = View.GONE
                    lifecycleScope.launch {
                        val defaultList = searchRepo.searchPlaces("", userLoc)
                        searchAdapter.updatePlaces(defaultList)
                    }
                }
                binding.cardSearchResults.showAnimated()
                binding.containerFloatingButtons.hideAnimated()
                binding.btnRecenterFloating.hideAnimated()
                binding.cardPinSelectionCallout.hideAnimated()
                binding.btnClearSearch.visibility = View.VISIBLE
            } else {
                binding.layoutSearchHistoryHeader.visibility = View.GONE
                binding.btnClearSearch.visibility = View.VISIBLE
                binding.containerFloatingButtons.hideAnimated()
                binding.btnRecenterFloating.hideAnimated()
                binding.cardPinSelectionCallout.hideAnimated()
            }
        }

        binding.etSearchPlaces.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchHistoryOrSuggestions(binding.etSearchPlaces.text.toString())
            }
        }

        binding.btnClearSearchHistory.setOnClickListener {
            searchHistoryRepo.clearHistory()
            showSearchHistoryOrSuggestions("")
            Toast.makeText(this, "Search history cleared", Toast.LENGTH_SHORT).show()
        }

        binding.etSearchPlaces.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                searchJob?.cancel()
                if (query.isNotEmpty()) {
                    showSearchHistoryOrSuggestions(query)
                    searchJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(250) // Debounce typing
                        val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation
                        val results = searchRepo.searchPlaces(
                            query = query,
                            userLocation = userLoc,
                            apiKey = viewModel.googleApiKey.value,
                            provider = viewModel.searchProvider.value
                        )
                        searchAdapter.updatePlaces(results)
                        binding.cardSearchResults.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                } else if (binding.etSearchPlaces.hasFocus()) {
                    showSearchHistoryOrSuggestions("")
                } else {
                    hideSearchDropdown()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearchPlaces.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearchPlaces.text.toString()
                searchJob?.cancel()
                lifecycleScope.launch {
                    val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation
                    val results = searchRepo.searchPlaces(
                        query = query,
                        userLocation = userLoc,
                        apiKey = viewModel.googleApiKey.value,
                        provider = viewModel.searchProvider.value
                    )
                    if (results.isNotEmpty()) {
                        onPlaceSelected(results.first())
                    }
                }
                true
            } else {
                false
            }
        }

        binding.btnClearSearch.setOnClickListener {
            if (binding.etSearchPlaces.text.isNotEmpty()) {
                binding.etSearchPlaces.text.clear()
                showSearchHistoryOrSuggestions("")
            } else {
                hideSearchDropdown()
            }
        }
    }

    private fun onPlaceSelected(place: SearchPlace) {
        searchHistoryRepo.addRecentSearch(place)
        hideSearchDropdown()
        binding.etSearchPlaces.setText(place.nameEn)
        
        // 1. Drop selection pin on map
        selectedPinLocation = place.coordinates
        mapEngine.setSelectionPoint(place.coordinates)

        // 2. Populate Place Info Callout Card
        val userLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation ?: GeoUtils.BAGHDAD_CENTER
        val distance = GeoUtils.calculateDistance(userLoc, place.coordinates)
        val nameText = if (place.nameAr.isNotBlank() && place.nameAr != place.nameEn) {
            "${place.nameEn} (${place.nameAr})"
        } else {
            place.nameEn
        }
        binding.tvPinLocationTitle.text = nameText
        binding.tvPinLocationSubtitle.text = "${place.district.ifBlank { "Baghdad" }} • ${GeoUtils.formatDistance(distance)} away • ${place.sourceProvider}"
        
        // 3. Show Place Info Callout Card
        binding.cardPinSelectionCallout.showAnimated()

        // 4. Center camera directly on searched place (Do not start route yet!)
        mapEngine.animateTo(place.coordinates, 16.toByte(), durationMs = 450L)
    }

    private fun hideSearchDropdown() {
        binding.cardSearchResults.hideAnimated()
        binding.layoutSearchHistoryHeader.visibility = View.GONE
        binding.containerFloatingButtons.showAnimated()
        if (mapEngine.isTrackingSuspended) {
            binding.btnRecenterFloating.showAnimated()
        }
        binding.etSearchPlaces.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchPlaces.windowToken, 0)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun setupUI() {
        // Bottom Sheet
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetRoute.routeDetailsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.peekHeight = 220

        // Instructions List
        instructionAdapter = InstructionAdapter()
        binding.bottomSheetRoute.recyclerInstructions.layoutManager = LinearLayoutManager(this)
        binding.bottomSheetRoute.recyclerInstructions.adapter = instructionAdapter

        // Vehicle Mode Toggles (Integrated in Bottom Sheet)
        binding.bottomSheetRoute.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnModeCar -> "car"
                    R.id.btnModeBike -> "bike"
                    R.id.btnModeFoot -> "foot"
                    else -> "car"
                }
                viewModel.setVehicleMode(mode)
            }
        }

        // Point Picker rows (Integrated in Bottom Sheet)
        binding.bottomSheetRoute.rowStartPoint.setOnClickListener { showLandmarkPicker(isSelectingStart = true) }
        binding.bottomSheetRoute.rowDestPoint.setOnClickListener { showLandmarkPicker(isSelectingStart = false) }

        // Clear button (Integrated in Bottom Sheet)
        binding.bottomSheetRoute.btnClearRoute.setOnClickListener {
            viewModel.clearRoute()
            mapEngine.clearAllMarkersAndRoute()
            mapEngine.clearSelectionPoint()
            binding.cardPinSelectionCallout.hideAnimated()
            binding.etSearchPlaces.text.clear()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            lastCalculatedStartLocation = null
        }

        // Pin Selection Callout Button: Set as Start
        binding.btnPinSetStart.setOnClickListener {
            val pin = selectedPinLocation ?: return@setOnClickListener
            viewModel.setStartPoint(pin)
            isStartPointDynamicGps = false // User manually selected fixed start point!
            val startTitle = binding.tvPinLocationTitle.text.toString()
            binding.bottomSheetRoute.tvStartPointLabel.text = "Start: $startTitle"
            mapEngine.clearSelectionPoint()
            binding.cardPinSelectionCallout.hideAnimated()

            if (viewModel.destPoint.value != null) {
                viewModel.calculateRoute()
            }
        }

        // Pin Selection Callout Button: Directions (Set as Destination & Calculate Route)
        binding.btnPinSetDest.setOnClickListener {
            val pin = selectedPinLocation ?: return@setOnClickListener
            viewModel.setDestinationPoint(pin)
            val destTitle = binding.tvPinLocationTitle.text.toString()
            binding.bottomSheetRoute.tvDestPointLabel.text = "Dest: $destTitle"
            mapEngine.clearSelectionPoint()
            binding.cardPinSelectionCallout.hideAnimated()

            // If no manual start point set, default to live GPS user location
            if (viewModel.startPoint.value == null || isStartPointDynamicGps) {
                isStartPointDynamicGps = true
                val currentLoc = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapEngine.lastUserLocation ?: GeoUtils.BAGHDAD_CENTER
                viewModel.setStartPoint(currentLoc)
                binding.bottomSheetRoute.tvStartPointLabel.text = if (lastGpsLocation != null) "Start: My Location (Live GPS)" else "Start: Baghdad Center"
                lastCalculatedStartLocation = currentLoc
            }

            viewModel.calculateRoute()
        }

        // Pin Selection Callout Button: Close
        binding.btnPinClose.setOnClickListener {
            selectedPinLocation = null
            mapEngine.clearSelectionPoint()
            binding.cardPinSelectionCallout.hideAnimated()
        }

        // Floating Re-center Button
        binding.btnRecenterFloating.setOnClickListener {
            mapEngine.resumeTracking(animated = true)
            binding.btnRecenterFloating.hideAnimated()
        }

        // Settings Dialog (⚙️ in search bar)
        binding.btnOpenSettings.setOnClickListener {
            val dialog = UnifiedSettingsDialog()
            dialog.currentTheme = mapEngine.currentThemePreset
            dialog.onThemeChanged = { newTheme ->
                mapEngine.setMapTheme(newTheme)
            }
            dialog.show(supportFragmentManager, UnifiedSettingsDialog.TAG)
        }

        // Compass / Tracking & Rotation FAB (🧭)
        binding.fabCompassTracking.setOnClickListener {
            val current = mapEngine.trackingMode
            val next = when (current) {
                MapEngine.TrackingMode.FREE -> MapEngine.TrackingMode.FOLLOW
                MapEngine.TrackingMode.FOLLOW -> MapEngine.TrackingMode.FOLLOW_AND_ROTATE
                MapEngine.TrackingMode.FOLLOW_AND_ROTATE -> MapEngine.TrackingMode.FREE
            }
            mapEngine.setTrackingMode(next, animated = true)

            when (next) {
                MapEngine.TrackingMode.FREE -> {
                    binding.fabCompassTracking.setImageResource(R.drawable.ic_compass)
                    Toast.makeText(this, "✋ Mode: Free Pan", Toast.LENGTH_SHORT).show()
                }
                MapEngine.TrackingMode.FOLLOW -> {
                    binding.fabCompassTracking.setImageResource(R.drawable.ic_my_location)
                    Toast.makeText(this, "📍 Mode: Follow My Location", Toast.LENGTH_SHORT).show()
                }
                MapEngine.TrackingMode.FOLLOW_AND_ROTATE -> {
                    binding.fabCompassTracking.setImageResource(R.drawable.ic_navigation_arrow)
                    Toast.makeText(this, "🧭 Mode: Follow & Rotate Map (Turn-by-Turn)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Map Themes FAB (🥞)
        binding.fabMapStyle.setOnClickListener {
            val dialog = UnifiedSettingsDialog()
            dialog.currentTheme = mapEngine.currentThemePreset
            dialog.onThemeChanged = { newTheme ->
                mapEngine.setMapTheme(newTheme)
            }
            dialog.show(supportFragmentManager, UnifiedSettingsDialog.TAG)
        }

        // Center Location FAB (🎯)
        binding.fabCenterBaghdad.setOnClickListener {
            mapEngine.setTrackingMode(MapEngine.TrackingMode.FOLLOW, animated = true)
            startLocationUpdates()
        }

        // Quick Route Calculate FAB
        binding.fabCalculateRoute.setOnClickListener {
            if (viewModel.startPoint.value != null && viewModel.destPoint.value != null) {
                viewModel.calculateRoute()
            } else {
                Toast.makeText(this, "Please pick both Start and Destination points first", Toast.LENGTH_SHORT).show()
                showLandmarkPicker(isSelectingStart = viewModel.startPoint.value == null)
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)

            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNetwork

            if (best != null) {
                onLocationChanged(best)
            } else {
                mapEngine.animateTo(GeoUtils.BAGHDAD_CENTER, 14.toByte(), durationMs = 450L)
            }
        } catch (e: Exception) {
            mapEngine.animateTo(GeoUtils.BAGHDAD_CENTER, 14.toByte(), durationMs = 450L)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastGpsLocation = location
        val userLatLong = LatLong(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing else compassManager.onAzimuthChanged?.let { mapEngine.lastUserBearing } ?: 0f
        mapEngine.setUserLocation(userLatLong, location.accuracy, bearing)

        // Only set initial start point once if not set yet
        if (isStartPointDynamicGps && viewModel.startPoint.value == null) {
            viewModel.setStartPoint(userLatLong)
            binding.bottomSheetRoute.tvStartPointLabel.text = "Start: My Location (Live GPS)"
        }
    }

    private fun showLandmarkPicker(isSelectingStart: Boolean) {
        val sheet = LocationPickerSheet.newInstance(isSelectingStart)
        sheet.onLocationSelected = { latLong, name ->
            if (isSelectingStart) {
                viewModel.setStartPoint(latLong)
                binding.bottomSheetRoute.tvStartPointLabel.text = "Start: $name"
            } else {
                viewModel.setDestinationPoint(latLong)
                binding.bottomSheetRoute.tvDestPointLabel.text = "Dest: $name"
            }
        }
        sheet.show(supportFragmentManager, LocationPickerSheet.TAG)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.startPoint.collectLatest { point ->
                mapEngine.setStartPoint(point)
            }
        }

        lifecycleScope.launch {
            viewModel.destPoint.collectLatest { point ->
                mapEngine.setDestinationPoint(point)
            }
        }

        lifecycleScope.launch {
            viewModel.installedMapFile.collectLatest { mapFile ->
                if (mapFile != null && mapFile.exists()) {
                    mapEngine.loadMapFile(mapFile)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.routeState.collectLatest { state ->
                when (state) {
                    is RouteState.Idle -> {
                        binding.progressBarRouting.visibility = View.GONE
                    }
                    is RouteState.Loading -> {
                        binding.progressBarRouting.visibility = View.VISIBLE
                    }
                    is RouteState.Success -> {
                        binding.progressBarRouting.visibility = View.GONE
                        displayRouteResult(state.result)
                    }
                    is RouteState.Error -> {
                        binding.progressBarRouting.visibility = View.GONE
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun displayRouteResult(route: RouteResult) {
        mapEngine.displayRoute(route.points, route.boundingBox)

        binding.bottomSheetRoute.tvRouteDistance.text = GeoUtils.formatDistance(route.distanceMeters)
        binding.bottomSheetRoute.tvRouteDuration.text = GeoUtils.formatDuration(route.timeMillis)
        binding.bottomSheetRoute.tvRouteSummary.text = route.summary.ifBlank { "Via Baghdad Street Network" }

        binding.bottomSheetRoute.chipOfflineStatus.text = route.source.displayName

        if (route.trafficDelayMins > 0) {
            binding.bottomSheetRoute.tvTrafficDelay.visibility = View.VISIBLE
            binding.bottomSheetRoute.tvTrafficDelay.text = "🚦 +${route.trafficDelayMins} min traffic"
            binding.bottomSheetRoute.tvTrafficDelay.setTextColor(android.graphics.Color.parseColor("#E65100"))
        } else if (route.source == com.offlinemap.baghdad.data.model.RoutingSource.GOOGLE_LIVE_TRAFFIC) {
            binding.bottomSheetRoute.tvTrafficDelay.visibility = View.VISIBLE
            binding.bottomSheetRoute.tvTrafficDelay.text = "🟢 Fast Traffic"
            binding.bottomSheetRoute.tvTrafficDelay.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        } else {
            binding.bottomSheetRoute.tvTrafficDelay.visibility = View.GONE
        }

        instructionAdapter.updateInstructions(route.instructions)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    override fun onResume() {
        super.onResume()
        mapEngine.onResume()
        compassManager.start()
        startLocationUpdates()
        viewModel.checkLocalFiles()
    }

    override fun onPause() {
        super.onPause()
        mapEngine.onPause()
        compassManager.stop()
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mapEngine.onDestroy()
    }
}

