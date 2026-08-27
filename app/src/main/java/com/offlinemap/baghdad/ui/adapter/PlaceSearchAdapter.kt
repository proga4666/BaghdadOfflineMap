package com.offlinemap.baghdad.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinemap.baghdad.data.repository.SearchPlace
import com.offlinemap.baghdad.databinding.ItemSearchPlaceBinding
import com.offlinemap.baghdad.utils.GeoUtils

class PlaceSearchAdapter(
    private var places: List<SearchPlace> = emptyList(),
    private val onPlaceClick: (SearchPlace) -> Unit
) : RecyclerView.Adapter<PlaceSearchAdapter.PlaceViewHolder>() {

    fun updatePlaces(newPlaces: List<SearchPlace>) {
        places = newPlaces
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemSearchPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(places[position])
    }

    override fun getItemCount(): Int = places.size

    inner class PlaceViewHolder(private val binding: ItemSearchPlaceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(place: SearchPlace) {
            binding.tvPlaceCategoryIcon.text = place.category.iconRes
            binding.tvPlaceName.text = place.nameEn
            binding.tvPlaceSubtitle.text = "${place.nameAr} • ${place.district}"
            binding.tvPlaceSourceBadge.text = place.sourceProvider

            if (place.distanceMeters > 0) {
                binding.tvPlaceDistance.text = GeoUtils.formatDistance(place.distanceMeters)
                binding.tvPlaceDistance.visibility = android.view.View.VISIBLE
            } else {
                binding.tvPlaceDistance.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener {
                onPlaceClick(place)
            }
        }
    }
}
