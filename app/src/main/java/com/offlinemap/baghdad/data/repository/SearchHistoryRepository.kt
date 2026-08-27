package com.offlinemap.baghdad.data.repository

import android.content.Context
import com.offlinemap.baghdad.utils.GeoUtils
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong

class SearchHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences("baghdad_search_history", Context.MODE_PRIVATE)

    fun getRecentSearches(userLocation: LatLong? = null): List<SearchPlace> {
        val jsonStr = prefs.getString("recent_places_json", null) ?: return emptyList()
        val list = mutableListOf<SearchPlace>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val lat = obj.getDouble("lat")
                val lon = obj.getDouble("lon")
                val categoryName = obj.optString("category", PlaceCategory.POI.name)
                val category = try {
                    PlaceCategory.valueOf(categoryName)
                } catch (e: Exception) {
                    PlaceCategory.POI
                }

                val place = SearchPlace(
                    id = obj.getString("id"),
                    nameEn = obj.getString("nameEn"),
                    nameAr = obj.getString("nameAr"),
                    district = obj.optString("district", ""),
                    category = category,
                    coordinates = LatLong(lat, lon),
                    sourceProvider = "Recent Search 🕒"
                )
                if (userLocation != null) {
                    place.distanceMeters = GeoUtils.calculateDistance(userLocation, place.coordinates)
                }
                list.add(place)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addRecentSearch(place: SearchPlace) {
        val current = getRecentSearches().toMutableList()
        // Remove existing if duplicate coordinates or id
        current.removeAll { 
            it.id == place.id || 
            (it.coordinates.latitude == place.coordinates.latitude && it.coordinates.longitude == place.coordinates.longitude) ||
            it.nameEn.equals(place.nameEn, ignoreCase = true)
        }
        // Add to top
        current.add(0, place.copy(sourceProvider = "Recent Search 🕒"))

        // Keep max 15
        val trimmed = current.take(15)

        val array = JSONArray()
        for (p in trimmed) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("nameEn", p.nameEn)
                put("nameAr", p.nameAr)
                put("district", p.district)
                put("category", p.category.name)
                put("lat", p.coordinates.latitude)
                put("lon", p.coordinates.longitude)
            }
            array.put(obj)
        }
        prefs.edit().putString("recent_places_json", array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("recent_places_json").apply()
    }
}
