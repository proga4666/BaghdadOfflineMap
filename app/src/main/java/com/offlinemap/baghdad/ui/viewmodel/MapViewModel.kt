package com.offlinemap.baghdad.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinemap.baghdad.data.model.MapPackage
import com.offlinemap.baghdad.data.model.POILocation
import com.offlinemap.baghdad.data.model.RouteResult
import com.offlinemap.baghdad.data.repository.DownloadProgress
import com.offlinemap.baghdad.data.repository.MapDataRepository
import com.offlinemap.baghdad.engine.PreferredRoutingProvider
import com.offlinemap.baghdad.engine.RoutingEngine
import com.offlinemap.baghdad.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import java.io.File

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("baghdad_map_prefs", Context.MODE_PRIVATE)

    val repository = MapDataRepository(application)
    val routingEngine = RoutingEngine(application)
    val networkMonitor = NetworkMonitor(application)

    private val _startPoint = MutableStateFlow<LatLong?>(null)
    val startPoint: StateFlow<LatLong?> = _startPoint.asStateFlow()

    private val _destPoint = MutableStateFlow<LatLong?>(null)
    val destPoint: StateFlow<LatLong?> = _destPoint.asStateFlow()

    private val _selectedVehicle = MutableStateFlow("car")
    val selectedVehicle: StateFlow<String> = _selectedVehicle.asStateFlow()

    private val _routeState = MutableStateFlow<RouteState>(RouteState.Idle)
    val routeState: StateFlow<RouteState> = _routeState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadProgress?>(null)
    val downloadState: StateFlow<DownloadProgress?> = _downloadState.asStateFlow()

    private val _installedMapFile = MutableStateFlow<File?>(null)
    val installedMapFile: StateFlow<File?> = _installedMapFile.asStateFlow()

    private val _googleApiKey = MutableStateFlow(prefs.getString("google_api_key", "") ?: "")
    val googleApiKey: StateFlow<String> = _googleApiKey.asStateFlow()

    private val _preferredProvider = MutableStateFlow(
        PreferredRoutingProvider.valueOf(
            prefs.getString("preferred_provider", PreferredRoutingProvider.AUTO.name) ?: PreferredRoutingProvider.AUTO.name
        )
    )
    val preferredProvider: StateFlow<PreferredRoutingProvider> = _preferredProvider.asStateFlow()

    private val _searchProvider = MutableStateFlow(
        try {
            com.offlinemap.baghdad.data.repository.SearchProvider.valueOf(
                prefs.getString("search_provider", com.offlinemap.baghdad.data.repository.SearchProvider.AUTO.name) ?: com.offlinemap.baghdad.data.repository.SearchProvider.AUTO.name
            )
        } catch (e: Exception) {
            com.offlinemap.baghdad.data.repository.SearchProvider.AUTO
        }
    )
    val searchProvider: StateFlow<com.offlinemap.baghdad.data.repository.SearchProvider> = _searchProvider.asStateFlow()

    fun setSearchProvider(provider: com.offlinemap.baghdad.data.repository.SearchProvider) {
        _searchProvider.value = provider
        prefs.edit().putString("search_provider", provider.name).apply()
    }

    init {
        checkLocalFiles()
    }

    fun checkLocalFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.extractBundledRoutingGraphIfNeeded()

            val mapFile = repository.findPrimaryMapFile()
            _installedMapFile.value = mapFile

            val ghFolder = repository.findPrimaryRoutingGraph()
            if (ghFolder != null) {
                val loaded = routingEngine.loadRoutingGraph(ghFolder)
                android.util.Log.d("MapViewModel", "GraphHopper offline routing graph loaded: $loaded from ${ghFolder.name}")
            }
        }
    }

    fun setGoogleApiKey(key: String) {
        _googleApiKey.value = key.trim()
        prefs.edit().putString("google_api_key", key.trim()).apply()
        if (_startPoint.value != null && _destPoint.value != null) {
            calculateRoute()
        }
    }

    fun setPreferredProvider(provider: PreferredRoutingProvider) {
        _preferredProvider.value = provider
        prefs.edit().putString("preferred_provider", provider.name).apply()
        if (_startPoint.value != null && _destPoint.value != null) {
            calculateRoute()
        }
    }

    fun setStartPoint(latLong: LatLong?) {
        _startPoint.value = latLong
        if (latLong != null && _destPoint.value != null) {
            calculateRoute()
        }
    }

    fun setDestinationPoint(latLong: LatLong?) {
        _destPoint.value = latLong
        if (_startPoint.value != null && latLong != null) {
            calculateRoute()
        }
    }

    fun setVehicleMode(vehicle: String) {
        if (_selectedVehicle.value != vehicle) {
            _selectedVehicle.value = vehicle
            if (_startPoint.value != null && _destPoint.value != null) {
                calculateRoute()
            }
        }
    }

    fun calculateRoute() {
        val start = _startPoint.value ?: return
        val dest = _destPoint.value ?: return

        viewModelScope.launch {
            _routeState.value = RouteState.Loading
            val isOnline = networkMonitor.checkCurrentConnectivity()

            val result = routingEngine.calculateRoute(
                start = start,
                dest = dest,
                vehicle = _selectedVehicle.value,
                provider = _preferredProvider.value,
                googleApiKey = _googleApiKey.value,
                isOnline = isOnline
            )

            result.fold(
                onSuccess = { routeResult ->
                    _routeState.value = RouteState.Success(routeResult)
                },
                onFailure = { error ->
                    _routeState.value = RouteState.Error(error.localizedMessage ?: "Failed to calculate route")
                }
            )
        }
    }

    fun clearRoute() {
        _startPoint.value = null
        _destPoint.value = null
        _routeState.value = RouteState.Idle
    }

    fun downloadMap(mapPackage: MapPackage) {
        viewModelScope.launch {
            repository.downloadMapFile(mapPackage).collect { progress ->
                _downloadState.value = progress
                if (progress is DownloadProgress.Completed) {
                    _installedMapFile.value = progress.file
                }
            }
        }
    }

    fun getCachedRoutesCount(): Int = routingEngine.cacheManager.getCachedRoutesCount()
    fun getLearnedEdgesCount(): Int = routingEngine.edgeStore.getLearnedEdgesCount()

    override fun onCleared() {
        super.onCleared()
        routingEngine.close()
    }
}

sealed class RouteState {
    object Idle : RouteState()
    object Loading : RouteState()
    data class Success(val result: RouteResult) : RouteState()
    data class Error(val message: String) : RouteState()
}
