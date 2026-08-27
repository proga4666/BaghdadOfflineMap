package com.offlinemap.baghdad.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.offlinemap.baghdad.databinding.DialogUnifiedSettingsBinding
import com.offlinemap.baghdad.engine.MapThemePreset
import com.offlinemap.baghdad.engine.PreferredRoutingProvider
import com.offlinemap.baghdad.ui.viewmodel.MapViewModel

class UnifiedSettingsDialog : BottomSheetDialogFragment() {

    private var _binding: DialogUnifiedSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()

    var onThemeChanged: ((MapThemePreset) -> Unit)? = null
    var currentTheme: MapThemePreset = MapThemePreset.WAZE_DARK

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUnifiedSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Theme state
        when (currentTheme) {
            MapThemePreset.WAZE_DARK -> binding.radioThemeWazeDark.isChecked = true
            MapThemePreset.WAZE_LIGHT -> binding.radioThemeWazeLight.isChecked = true
            MapThemePreset.MODERN_LIGHT -> binding.radioThemeLightModern.isChecked = true
            MapThemePreset.MIDNIGHT_DARK -> binding.radioThemeDarkMidnight.isChecked = true
            MapThemePreset.OSM_CLASSIC -> binding.radioThemeWazeDark.isChecked = true
        }

        // 2. Routing Provider state
        when (viewModel.preferredProvider.value) {
            PreferredRoutingProvider.AUTO -> binding.radioAuto.isChecked = true
            PreferredRoutingProvider.GOOGLE_TRAFFIC -> binding.radioGoogleTraffic.isChecked = true
            PreferredRoutingProvider.OFFLINE_GRAPHHOPPER -> binding.radioOfflineGraphHopper.isChecked = true
        }

        // 3. Search Provider state
        when (viewModel.searchProvider.value) {
            com.offlinemap.baghdad.data.repository.SearchProvider.AUTO -> binding.radioSearchAuto.isChecked = true
            com.offlinemap.baghdad.data.repository.SearchProvider.GOOGLE_PLACES -> binding.radioSearchGoogle.isChecked = true
            com.offlinemap.baghdad.data.repository.SearchProvider.PHOTON_OSM -> binding.radioSearchPhoton.isChecked = true
            com.offlinemap.baghdad.data.repository.SearchProvider.LOCAL_OFFLINE -> binding.radioSearchOffline.isChecked = true
        }

        binding.etGoogleApiKey.setText(viewModel.googleApiKey.value)

        // 4. Offline map state
        val installedMap = viewModel.installedMapFile.value
        if (installedMap != null && installedMap.exists()) {
            val sizeMb = installedMap.length() / (1024 * 1024)
            binding.tvActiveMapStatus.text = "Active Map: ${installedMap.name} (${sizeMb} MB)"
        } else {
            binding.tvActiveMapStatus.text = "No offline map installed"
        }

        // 5. Learning stats
        val tripsCount = viewModel.getCachedRoutesCount()
        val corridorsCount = viewModel.getLearnedEdgesCount()
        binding.tvLearningStats.text = "💾 Saved Trips: $tripsCount  •  🧠 Google-Preferred Corridors: $corridorsCount"

        binding.btnManageOfflinePackages.setOnClickListener {
            dismiss()
            DownloadMapDialog().show(parentFragmentManager, DownloadMapDialog.TAG)
        }

        binding.btnSaveAllSettings.setOnClickListener {
            // Save API key
            val key = binding.etGoogleApiKey.text?.toString() ?: ""
            viewModel.setGoogleApiKey(key)

            // Save Routing Provider
            val selectedRoutingProvider = when (binding.radioGroupRoutingMode.checkedRadioButtonId) {
                binding.radioGoogleTraffic.id -> PreferredRoutingProvider.GOOGLE_TRAFFIC
                binding.radioOfflineGraphHopper.id -> PreferredRoutingProvider.OFFLINE_GRAPHHOPPER
                else -> PreferredRoutingProvider.AUTO
            }
            viewModel.setPreferredProvider(selectedRoutingProvider)

            // Save Search Provider
            val selectedSearchProvider = when (binding.radioGroupSearchMode.checkedRadioButtonId) {
                binding.radioSearchGoogle.id -> com.offlinemap.baghdad.data.repository.SearchProvider.GOOGLE_PLACES
                binding.radioSearchPhoton.id -> com.offlinemap.baghdad.data.repository.SearchProvider.PHOTON_OSM
                binding.radioSearchOffline.id -> com.offlinemap.baghdad.data.repository.SearchProvider.LOCAL_OFFLINE
                else -> com.offlinemap.baghdad.data.repository.SearchProvider.AUTO
            }
            viewModel.setSearchProvider(selectedSearchProvider)

            // Save Theme
            val selectedTheme = when (binding.radioGroupMapThemes.checkedRadioButtonId) {
                binding.radioThemeWazeLight.id -> MapThemePreset.WAZE_LIGHT
                binding.radioThemeLightModern.id -> MapThemePreset.MODERN_LIGHT
                binding.radioThemeDarkMidnight.id -> MapThemePreset.MIDNIGHT_DARK
                else -> MapThemePreset.WAZE_DARK
            }

            val prefs = requireContext().getSharedPreferences("baghdad_map_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("map_theme_preset", selectedTheme.name).apply()

            onThemeChanged?.invoke(selectedTheme)
            Toast.makeText(requireContext(), "Settings saved!", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "UnifiedSettingsDialog"
    }
}
