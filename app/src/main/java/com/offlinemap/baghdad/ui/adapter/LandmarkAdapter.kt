package com.offlinemap.baghdad.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinemap.baghdad.data.model.POILocation
import com.offlinemap.baghdad.databinding.ItemLandmarkBinding
import java.util.Locale

class LandmarkAdapter(
    private val landmarks: List<POILocation>,
    private val onLandmarkClick: (POILocation) -> Unit
) : RecyclerView.Adapter<LandmarkAdapter.LandmarkViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LandmarkViewHolder {
        val binding = ItemLandmarkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LandmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LandmarkViewHolder, position: Int) {
        holder.bind(landmarks[position])
    }

    override fun getItemCount(): Int = landmarks.size

    inner class LandmarkViewHolder(private val binding: ItemLandmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(poi: POILocation) {
            binding.tvLandmarkName.text = poi.name
            binding.tvLandmarkCoords.text = String.format(
                Locale.getDefault(),
                "%s • (%.4f, %.4f)",
                poi.description,
                poi.latLong.latitude,
                poi.latLong.longitude
            )
            binding.root.setOnClickListener {
                onLandmarkClick(poi)
            }
        }
    }
}
