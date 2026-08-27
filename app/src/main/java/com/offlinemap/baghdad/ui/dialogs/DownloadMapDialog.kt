package com.offlinemap.baghdad.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.offlinemap.baghdad.data.model.MapPackage
import com.offlinemap.baghdad.data.repository.DownloadProgress
import com.offlinemap.baghdad.databinding.DialogDownloadMapBinding
import com.offlinemap.baghdad.ui.viewmodel.MapViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadMapDialog : BottomSheetDialogFragment() {

    private var _binding: DialogDownloadMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDownloadMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatus()

        val packages = viewModel.repository.getAvailablePackages()
        val mansourPkg = packages.firstOrNull { it.id == "baghdad_central" }
        val iraqPkg = packages.firstOrNull { it.id == "iraq_mapsforge" }

        binding.btnCloseDialog.setOnClickListener {
            dismiss()
        }

        binding.btnDownloadMansour.setOnClickListener {
            if (mansourPkg != null) {
                startDownload(mansourPkg)
            }
        }

        binding.btnDownloadIraq.setOnClickListener {
            if (iraqPkg != null) {
                startDownload(iraqPkg)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadState.collectLatest { state ->
                when (state) {
                    is DownloadProgress.Started -> {
                        binding.layoutDialogProgress.visibility = View.VISIBLE
                        binding.progressDownload.isIndeterminate = true
                        binding.tvDownloadProgressText.text = "Connecting to high-speed map server..."
                        binding.tvDownloadPercent.text = "0%"
                        setButtonsEnabled(false)
                    }
                    is DownloadProgress.Downloading -> {
                        binding.layoutDialogProgress.visibility = View.VISIBLE
                        binding.progressDownload.isIndeterminate = false
                        binding.progressDownload.progress = state.percent
                        val downloadedMb = String.format("%.1f", state.bytesDownloaded / (1024.0 * 1024.0))
                        val totalMb = String.format("%.1f", state.totalBytes / (1024.0 * 1024.0))
                        binding.tvDownloadProgressText.text = "Downloading: $downloadedMb MB / $totalMb MB"
                        binding.tvDownloadPercent.text = "${state.percent}%"
                        setButtonsEnabled(false)
                    }
                    is DownloadProgress.Completed -> {
                        binding.layoutDialogProgress.visibility = View.GONE
                        setButtonsEnabled(true)
                        updateStatus()
                        Toast.makeText(requireContext(), "Map installed successfully! Switching to offline mode.", Toast.LENGTH_SHORT).show()
                    }
                    is DownloadProgress.Failed -> {
                        binding.layoutDialogProgress.visibility = View.VISIBLE
                        binding.tvDownloadProgressText.text = "Error: ${state.error}"
                        binding.tvDownloadPercent.text = "Failed"
                        setButtonsEnabled(true)
                    }
                    null -> {
                        binding.layoutDialogProgress.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun startDownload(pkg: MapPackage) {
        binding.layoutDialogProgress.visibility = View.VISIBLE
        binding.progressDownload.isIndeterminate = true
        binding.tvDownloadProgressText.text = "Starting ${pkg.name} download..."
        setButtonsEnabled(false)
        viewModel.downloadMap(pkg)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnDownloadMansour.isEnabled = enabled
        binding.btnDownloadIraq.isEnabled = enabled
    }

    private fun updateStatus() {
        val mapFile = viewModel.repository.findPrimaryMapFile()
        val ghFolder = viewModel.repository.findPrimaryRoutingGraph()

        val mapStatus = if (mapFile != null && mapFile.exists()) {
            "⚡ Active Offline Vector Map: ${mapFile.name} (${String.format("%.1f", mapFile.length() / (1024.0 * 1024.0))} MB)"
        } else {
            "🌐 Active Map: Live Online OSM Tiles (Download below for 100% offline)"
        }

        binding.tvMapStatus.text = mapStatus
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DownloadMapDialog"
    }
}
