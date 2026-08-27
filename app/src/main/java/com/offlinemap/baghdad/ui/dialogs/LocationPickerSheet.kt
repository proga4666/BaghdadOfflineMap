package com.offlinemap.baghdad.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.offlinemap.baghdad.data.model.POILocation
import com.offlinemap.baghdad.databinding.SheetLocationPickerBinding
import com.offlinemap.baghdad.ui.adapter.LandmarkAdapter
import org.mapsforge.core.model.LatLong

class LocationPickerSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLocationPickerBinding? = null
    private val binding get() = _binding!!

    var isSelectingStartPoint: Boolean = true
    var onLocationSelected: ((LatLong, String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetLocationPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPickerTitle.text = if (isSelectingStartPoint) {
            "Select START Location (Baghdad)"
        } else {
            "Select DESTINATION Location (Baghdad)"
        }

        val adapter = LandmarkAdapter(POILocation.BAGHDAD_LANDMARKS) { poi ->
            onLocationSelected?.invoke(poi.latLong, poi.name)
            dismiss()
        }

        binding.recyclerLandmarks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLandmarks.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LocationPickerSheet"

        fun newInstance(isSelectingStart: Boolean): LocationPickerSheet {
            return LocationPickerSheet().apply {
                isSelectingStartPoint = isSelectingStart
            }
        }
    }
}
