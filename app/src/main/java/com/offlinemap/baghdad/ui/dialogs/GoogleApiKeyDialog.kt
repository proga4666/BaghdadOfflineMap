package com.offlinemap.baghdad.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.offlinemap.baghdad.databinding.DialogGoogleApiKeyBinding
import com.offlinemap.baghdad.engine.PreferredRoutingProvider
import com.offlinemap.baghdad.ui.viewmodel.MapViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GoogleApiKeyDialog : BottomSheetDialogFragment() {

    private var _binding: DialogGoogleApiKeyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGoogleApiKeyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etGoogleApiKey.setText(viewModel.googleApiKey.value)

        when (viewModel.preferredProvider.value) {
            PreferredRoutingProvider.AUTO -> binding.radioAuto.isChecked = true
            PreferredRoutingProvider.GOOGLE_TRAFFIC -> binding.radioGoogleTraffic.isChecked = true
            PreferredRoutingProvider.OFFLINE_GRAPHHOPPER -> binding.radioOfflineGraphHopper.isChecked = true
        }

        val cachedCount = viewModel.getCachedRoutesCount()
        val learnedEdges = viewModel.getLearnedEdgesCount()
        binding.tvCacheStats.text = "💾 Saved Trips: $cachedCount  •  🧠 Google-Preferred Corridors: $learnedEdges"

        binding.btnCancelSettings.setOnClickListener {
            dismiss()
        }

        binding.btnSaveSettings.setOnClickListener {
            val key = binding.etGoogleApiKey.text?.toString() ?: ""
            viewModel.setGoogleApiKey(key)

            val selectedProvider = when (binding.radioGroupRoutingMode.checkedRadioButtonId) {
                binding.radioGoogleTraffic.id -> PreferredRoutingProvider.GOOGLE_TRAFFIC
                binding.radioOfflineGraphHopper.id -> PreferredRoutingProvider.OFFLINE_GRAPHHOPPER
                else -> PreferredRoutingProvider.AUTO
            }
            viewModel.setPreferredProvider(selectedProvider)

            Toast.makeText(requireContext(), "Navigation settings saved!", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "GoogleApiKeyDialog"
    }
}
