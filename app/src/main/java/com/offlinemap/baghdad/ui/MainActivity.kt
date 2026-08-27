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
    private lateinit var compassManager: CompassSensorManager

    private var lastGpsLocation: Location? = null

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

    private fun setupMapView() {
        mapView = MapView(this)
        binding.mapContainer.addView(mapView)
        mapEngine = MapEngine(this, mapView)

        val savedThemeName = getSharedPreferences("baghdad_map_prefs", Context.MODE_PRIVATE)
            .getString("map_theme_preset", MapThemePreset.WAZE_DARK.name)
        val preset = try {
            MapThemePreset.valueOf(savedThemeName ?: "")
        } catch (e: Exception) {
            MapThemePreset.WAZE_DARK
        }
        mapEngine.setMapTheme(preset)

        mapEngine.onMapTapListener = { latLong ->
            if (binding.cardSearchResults.visibility == View.VISIBLE) {
                hideSearchDropdown()
            } else if (viewModel.startPoint.value == null) {
                viewModel.setStartPoint(latLong)
                binding.tvStartPointLabel.text = String.format(Locale.getDefault(), "Start: (%.4f, %.4f)", latLong.latitude, latLong.longitude)
                binding.cardRouteSummaryHeader.visibility = View.VISIBLE
                Toast.makeText(this, "Start point set. Tap again or search for destination.", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.setDestinationPoint(latLong)
                binding.tvDestPointLabel.text = String.format(Locale.getDefault(), "Dest: (%.4f, %.4f)", latLong.latitude, latLong.longitude)
                binding.cardRouteSummaryHeader.visibility = View.VISIBLE
            }
        }

        mapEngine.onMapLongClickListener = { latLong ->
            viewModel.setDestinationPoint(latLong)
            binding.tvDestPointLabel.text = String.format(Locale.getDefault(), "Dest: (%.4f, %.4f)", latLong.latitude, latLong.longitude)
            binding.cardRouteSummaryHeader.visibility = View.VISIBLE
            Toast.makeText(this, "Destination updated from long press", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCompassAndLocation() {
        compassManager = CompassSensorManager(this)
        compassManager.onAzimuthChanged = { azimuth ->
            val loc = lastGpsLocation
            val latLong = if (loc != null) LatLong(loc.latitude, loc.longitude) else mapEngine.lastUserLocation
            val accuracy = loc?.accuracy ?: 20f
            mapEngine.setUserLocation(latLong, accuracy, azimuth)
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun setupSearch() {
        searchAdapter = PlaceSearchAdapter { place ->
            onPlaceSelected(place)
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter

        binding.etSearchPlaces.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                searchJob?.cancel()
                if (query.isNotEmpty()) {
                    binding.btnClearSearch.visibility = View.VISIBLE
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
                } else {
                    binding.btnClearSearch.visibility = View.GONE
                    binding.cardSearchResults.visibility = View.GONE
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
            binding.etSearchPlaces.text.clear()
            hideSearchDropdown()
        }
    }

    private fun onPlaceSelected(place: SearchPlace) {
        hideSearchDropdown()
        binding.etSearchPlaces.setText(place.nameEn)

        // Set destination
        viewModel.setDestinationPoint(place.coordinates)
        binding.tvDestPointLabel.text = "${place.nameEn} (${place.nameAr})"
        binding.cardRouteSummaryHeader.visibility = View.VISIBLE

        // If start point is not set, default to user's current GPS location
        if (viewModel.startPoint.value == null) {
            val userLatLong = lastGpsLocation?.let { LatLong(it.latitude, it.longitude) } ?: GeoUtils.BAGHDAD_CENTER
            viewModel.setStartPoint(userLatLong)
            binding.tvStartPointLabel.text = if (lastGpsLocation != null) "My Location" else "Baghdad Center"
        }

        mapEngine.centerOn(place.coordinates, 15.toByte())
        viewModel.calculateRoute()
    }

    private fun hideSearchDropdown() {
        binding.cardSearchResults.visibility = View.GONE
        binding.etSearchPlaces.clearFocus()
    }

    private fun setupUI() {
        // Bottom Sheet
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetRoute.routeDetailsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.peekHeight = 260

        // Instructions List
        instructionAdapter = InstructionAdapter()
        binding.bottomSheetRoute.recyclerInstructions.layoutManager = LinearLayoutManager(this)
        binding.bottomSheetRoute.recyclerInstructions.adapter = instructionAdapter

        // Vehicle Mode Toggles
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
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

        // Point Picker rows
        binding.rowStartPoint.setOnClickListener { showLandmarkPicker(isSelectingStart = true) }
        binding.rowDestPoint.setOnClickListener { showLandmarkPicker(isSelectingStart = false) }

        // Clear button
        binding.btnClearRoute.setOnClickListener {
            viewModel.clearRoute()
            mapEngine.clearAllMarkersAndRoute()
            binding.cardRouteSummaryHeader.visibility = View.GONE
            binding.etSearchPlaces.text.clear()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
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
            mapEngine.setTrackingMode(next)

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
                mapEngine.centerOn(LatLong(best.latitude, best.longitude), 15.toByte())
            } else {
                mapEngine.centerOn(GeoUtils.BAGHDAD_CENTER, 14.toByte())
            }
        } catch (e: Exception) {
            mapEngine.centerOn(GeoUtils.BAGHDAD_CENTER, 14.toByte())
        }
    }

    override fun onLocationChanged(location: Location) {
        lastGpsLocation = location
        val userLatLong = LatLong(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing else compassManager.onAzimuthChanged?.let { mapEngine.lastUserBearing } ?: 0f
        mapEngine.setUserLocation(userLatLong, location.accuracy, bearing)
    }

    private fun showLandmarkPicker(isSelectingStart: Boolean) {
        val sheet = LocationPickerSheet.newInstance(isSelectingStart)
        sheet.onLocationSelected = { latLong, name ->
            if (isSelectingStart) {
                viewModel.setStartPoint(latLong)
                binding.tvStartPointLabel.text = "Start: $name"
            } else {
                viewModel.setDestinationPoint(latLong)
                binding.tvDestPointLabel.text = "Dest: $name"
            }
            binding.cardRouteSummaryHeader.visibility = View.VISIBLE
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

