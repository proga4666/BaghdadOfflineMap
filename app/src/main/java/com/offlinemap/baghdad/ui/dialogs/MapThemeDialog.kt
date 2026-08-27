package com.offlinemap.baghdad.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.offlinemap.baghdad.databinding.DialogMapThemeBinding
import com.offlinemap.baghdad.engine.MapThemePreset

class MapThemeDialog : BottomSheetDialogFragment() {

    private var _binding: DialogMapThemeBinding? = null
    private val binding get() = _binding!!

    var onThemeSelected: ((MapThemePreset) -> Unit)? = null
    var currentPreset: MapThemePreset = MapThemePreset.WAZE_DARK

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogMapThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when (currentPreset) {
            MapThemePreset.WAZE_DARK -> binding.radioThemeWazeDark.isChecked = true
            MapThemePreset.WAZE_LIGHT -> binding.radioThemeWazeLight.isChecked = true
            MapThemePreset.MODERN_LIGHT -> binding.radioThemeLightModern.isChecked = true
            MapThemePreset.MIDNIGHT_DARK -> binding.radioThemeDarkMidnight.isChecked = true
            MapThemePreset.OSM_CLASSIC -> binding.radioThemeOsmClassic.isChecked = true
        }

        binding.radioGroupMapThemes.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                binding.radioThemeWazeLight.id -> MapThemePreset.WAZE_LIGHT
                binding.radioThemeLightModern.id -> MapThemePreset.MODERN_LIGHT
                binding.radioThemeDarkMidnight.id -> MapThemePreset.MIDNIGHT_DARK
                binding.radioThemeOsmClassic.id -> MapThemePreset.OSM_CLASSIC
                else -> MapThemePreset.WAZE_DARK
            }

            val prefs = requireContext().getSharedPreferences("baghdad_map_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("map_theme_preset", preset.name).apply()

            onThemeSelected?.invoke(preset)
            Toast.makeText(requireContext(), "Map style applied: ${preset.title}", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        binding.btnCloseThemeDialog.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MapThemeDialog"

        fun newInstance(current: MapThemePreset): MapThemeDialog {
            return MapThemeDialog().apply {
                currentPreset = current
            }
        }
    }
}
