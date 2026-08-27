package com.offlinemap.baghdad.data.repository

import android.content.Context
import com.offlinemap.baghdad.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SearchProvider(val displayName: String, val badge: String) {
    AUTO("🔄 Auto (Google -> Free Photon OSM -> Offline)", "Auto"),
    GOOGLE_PLACES("🟢 Google Places & Geocoding API", "Google"),
    PHOTON_OSM("🌐 Photon OpenStreetMap (100% Free)", "OSM"),
    LOCAL_OFFLINE("⚡ Local Offline Baghdad Index", "Offline")
}

enum class PlaceCategory(val displayName: String, val iconRes: String) {
    DISTRICT("District / Neighborhood", "📍"),
    MALL("Shopping & Malls", "🛍️"),
    UNIVERSITY("University & Education", "🎓"),
    HOSPITAL("Hospital & Healthcare", "🏥"),
    LANDMARK("Landmark & Monument", "🏛️"),
    AIRPORT("Airport & Transport", "✈️"),
    BRIDGE("Bridge", "🌉"),
    RESTAURANT("Dining & Cafes", "☕"),
    STREET("Street / Avenue", "🛣️"),
    POI("Place of Interest", "📌")
}

data class SearchPlace(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val district: String,
    val category: PlaceCategory,
    val coordinates: LatLong,
    var distanceMeters: Double = 0.0,
    val sourceProvider: String = "Offline"
)

class PlaceSearchRepository(private val context: Context? = null) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Fast in-memory cache of previously fetched search places
    private val memorySearchCache = mutableMapOf<String, SearchPlace>()

    private val offlinePlaces = listOf(
        // Districts
        SearchPlace("d1", "Al-Mansour", "المنصور", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3152, 44.3661)),
        SearchPlace("d2", "Al-Karrada", "الكرادة", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3082, 44.4285)),
        SearchPlace("d3", "Al-Jadriya", "الجادرية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.2750, 44.3820)),
        SearchPlace("d4", "Al-Yarmouk", "اليرموك", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3021, 44.3510)),
        SearchPlace("d5", "Al-Kadhimiya", "الكاظمية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3800, 44.3410)),
        SearchPlace("d6", "Al-Adhamiya", "الأعظمية", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3710, 44.3600)),
        SearchPlace("d7", "Al-Zayouna", "زيونة", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3320, 44.4550)),
        SearchPlace("d8", "Palestine Street", "شارع فلسطين", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3510, 44.4320)),
        SearchPlace("d9", "Al-Harthiya", "الحارثية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3100, 44.3750)),
        SearchPlace("d10", "Al-Saydiyah", "السيدية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.2620, 44.3510)),
        SearchPlace("d11", "Al-Qadisiya", "القادسية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.2920, 44.3810)),
        SearchPlace("d12", "Al-Doura", "الدورة", "Karkh", PlaceCategory.DISTRICT, LatLong(33.2450, 44.4020)),
        SearchPlace("d13", "Al-Shaab", "الشعب", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.4020, 44.4150)),
        SearchPlace("d14", "Al-Salihiya", "الصالحية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3250, 44.3980)),
        SearchPlace("d15", "Bab Al-Sharqi", "الباب الشرقي", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3330, 44.4120)),
        SearchPlace("d16", "Al-Ameriya", "العامرية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.2950, 44.2980)),
        SearchPlace("d17", "Al-Ghazaliya", "الغزالية", "Karkh", PlaceCategory.DISTRICT, LatLong(33.3280, 44.2750)),
        SearchPlace("d18", "Baghdad Al-Jadida", "بغداد الجديدة", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.2950, 44.4820)),
        SearchPlace("d19", "Al-Za'franiya", "الزعفرانية", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.2450, 44.4750)),
        SearchPlace("d20", "Al-Waziriyah", "الوزيرية", "Rusafa", PlaceCategory.DISTRICT, LatLong(33.3610, 44.3920)),

        // Landmarks & Monuments
        SearchPlace("l1", "Tahrir Square", "ساحة التحرير", "Rusafa", PlaceCategory.LANDMARK, LatLong(33.3300, 44.4110)),
        SearchPlace("l2", "Al-Mutanabbi Street", "شارع المتنبي", "Rusafa", PlaceCategory.LANDMARK, LatLong(33.3400, 44.3900)),
        SearchPlace("l3", "Al-Shaheed Monument", "نصب الشهيد", "Rusafa", PlaceCategory.LANDMARK, LatLong(33.3420, 44.4440)),
        SearchPlace("l4", "Al-Firdos Square", "ساحة الفردوس", "Rusafa", PlaceCategory.LANDMARK, LatLong(33.3130, 44.4230)),
        SearchPlace("l5", "Al-Zawra Park", "متنزه الزوراء", "Karkh", PlaceCategory.LANDMARK, LatLong(33.3180, 44.3820)),
        SearchPlace("l6", "Al-Qushla Clock Tower", "القشلة", "Rusafa", PlaceCategory.LANDMARK, LatLong(33.3410, 44.3890)),
        SearchPlace("l7", "Jazirat Al-A'ras", "جزيرة الأعراس", "Karkh", PlaceCategory.LANDMARK, LatLong(33.2820, 44.3920)),
        SearchPlace("l8", "Kadhimiya Holy Shrine", "الروضة الكاظمية المقدسة", "Al-Kadhimiya", PlaceCategory.LANDMARK, LatLong(33.3805, 44.3415)),
        SearchPlace("l9", "Abu Hanifa Mosque", "جامع الإمام الأعظم أبو حنيفة", "Al-Adhamiya", PlaceCategory.LANDMARK, LatLong(33.3730, 44.3580)),

        // Shopping & Malls
        SearchPlace("m1", "Baghdad Mall", "بغداد مول - الحارثية", "Al-Harthiya", PlaceCategory.MALL, LatLong(33.3080, 44.3720)),
        SearchPlace("m2", "Mansour Mall", "المنصور مول", "Al-Mansour", PlaceCategory.MALL, LatLong(33.3120, 44.3640)),
        SearchPlace("m3", "Babylon Mall", "بابلون مول", "Al-Mansour", PlaceCategory.MALL, LatLong(33.3190, 44.3590)),
        SearchPlace("m4", "Al-Nakhla Mall", "النخلة مول", "Al-Zayouna", PlaceCategory.MALL, LatLong(33.3340, 44.4510)),
        SearchPlace("m5", "Al-Zayouna Mall", "زيونة مول", "Al-Zayouna", PlaceCategory.MALL, LatLong(33.3310, 44.4580)),
        SearchPlace("m6", "Karrada Center Mall", "كرادة سنتر", "Al-Karrada", PlaceCategory.MALL, LatLong(33.3050, 44.4320)),

        // Universities
        SearchPlace("u1", "University of Baghdad (Jadriya)", "جامعة بغداد - مجمع الجادرية", "Al-Jadriya", PlaceCategory.UNIVERSITY, LatLong(33.2710, 44.3790)),
        SearchPlace("u2", "Al-Mustansiriya University", "الجامعة المستنصرية", "Palestine St", PlaceCategory.UNIVERSITY, LatLong(33.3550, 44.4100)),
        SearchPlace("u3", "University of Technology", "الجامعة التكنولوجية", "Sina'a St", PlaceCategory.UNIVERSITY, LatLong(33.3110, 44.4440)),
        SearchPlace("u4", "Al-Nahrain University", "جامعة النهرين", "Al-Jadriya", PlaceCategory.UNIVERSITY, LatLong(33.2790, 44.3800)),
        SearchPlace("u5", "Baghdad Medical City", "مدينة الطب", "Bab Al-Muadham", PlaceCategory.HOSPITAL, LatLong(33.3580, 44.3820)),

        // Hospitals
        SearchPlace("h1", "Ibn Sina Hospital", "مستشفى ابن سينا", "Al-Qadisiya", PlaceCategory.HOSPITAL, LatLong(33.2980, 44.3850)),
        SearchPlace("h2", "Al-Yarmouk Teaching Hospital", "مستشفى اليرموك التعليمي", "Al-Yarmouk", PlaceCategory.HOSPITAL, LatLong(33.2990, 44.3540)),
        SearchPlace("h3", "Al-Karkh Hospital", "مستشفى الكرخ", "Al-Mansour", PlaceCategory.HOSPITAL, LatLong(33.3210, 44.3680)),
        SearchPlace("h4", "Al-Shaheed Ghazi Al-Hariri Hospital", "مستشفى الشهيد غازي الحريري", "Medical City", PlaceCategory.HOSPITAL, LatLong(33.3590, 44.3830)),

        // Airports & Bridges
        SearchPlace("t1", "Baghdad International Airport (BGW)", "مطار بغداد الدولي", "Airport Road", PlaceCategory.AIRPORT, LatLong(33.2625, 44.2344)),
        SearchPlace("b1", "Al-Jadriya Bridge", "جسر الجادرية", "Jadriya / Qadisiya", PlaceCategory.BRIDGE, LatLong(33.2840, 44.3860)),
        SearchPlace("b2", "14th of July Bridge", "جسر 14 تموز", "Karkh / Karrada", PlaceCategory.BRIDGE, LatLong(33.3030, 44.4020)),
        SearchPlace("b3", "Al-Jumhuriya Bridge", "جسر الجمهورية", "Tahrir / Green Zone", PlaceCategory.BRIDGE, LatLong(33.3280, 44.4060)),
        SearchPlace("b4", "Al-Sarafiyah Bridge", "جسر الصرافية", "Waziriyah / Utaifiyah", PlaceCategory.BRIDGE, LatLong(33.3620, 44.3770)),
        SearchPlace("b5", "Al-A'immah Bridge", "جسر الأئمة", "Adhamiya / Kadhimiya", PlaceCategory.BRIDGE, LatLong(33.3760, 44.3510))
    )

    suspend fun searchPlaces(
        query: String,
        userLocation: LatLong? = null,
        apiKey: String? = null,
        provider: SearchProvider = SearchProvider.AUTO
    ): List<SearchPlace> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            val defaultList = offlinePlaces.take(8)
            if (userLocation != null) {
                defaultList.forEach { it.distanceMeters = GeoUtils.calculateDistance(userLocation, it.coordinates) }
            }
            return@withContext defaultList
        }

        // 1. If provider is GOOGLE_PLACES or (AUTO with apiKey), try Google Places API first
        if ((provider == SearchProvider.GOOGLE_PLACES || (provider == SearchProvider.AUTO && !apiKey.isNullOrBlank())) && !apiKey.isNullOrBlank()) {
            try {
                val googleResults = searchGooglePlaces(trimmed, apiKey)
                if (googleResults.isNotEmpty()) {
                    cacheResults(googleResults)
                    sortAndCalculateDistances(googleResults, userLocation)
                    return@withContext googleResults
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. If provider is PHOTON_OSM or AUTO, try Free Photon OpenStreetMap Geocoding
        if (provider == SearchProvider.PHOTON_OSM || provider == SearchProvider.AUTO) {
            try {
                val photonResults = searchPhotonOsm(trimmed)
                if (photonResults.isNotEmpty()) {
                    cacheResults(photonResults)
                    sortAndCalculateDistances(photonResults, userLocation)
                    return@withContext photonResults
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Fallback: Search in-memory cache & offline local database
        val offlineMatches = searchLocalOffline(trimmed)
        sortAndCalculateDistances(offlineMatches, userLocation)
        return@withContext offlineMatches
    }

    private fun searchPhotonOsm(query: String): List<SearchPlace> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://photon.komoot.io/api/?q=$encoded&lat=33.3152&lon=44.3661&limit=15&lang=default"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BaghdadOfflineMap/2.0")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val features = json.optJSONArray("features") ?: return emptyList()

        val list = mutableListOf<SearchPlace>()
        for (i in 0 until features.length()) {
            val item = features.getJSONObject(i)
            val props = item.optJSONObject("properties") ?: continue
            val geometry = item.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue

            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)

            val name = props.optString("name", "").ifBlank {
                props.optString("street", props.optString("district", props.optString("city", "")))
            }
            if (name.isBlank()) continue

            val street = props.optString("street", "")
            val district = props.optString("district", props.optString("locality", props.optString("city", "Baghdad")))
            val osmKey = props.optString("osm_key", "")
            val osmValue = props.optString("osm_value", "")

            val category = when {
                osmKey == "amenity" && osmValue in listOf("hospital", "clinic", "pharmacy") -> PlaceCategory.HOSPITAL
                osmKey == "amenity" && osmValue in listOf("university", "college", "school") -> PlaceCategory.UNIVERSITY
                osmKey == "shop" || osmValue == "mall" -> PlaceCategory.MALL
                osmKey == "highway" -> PlaceCategory.STREET
                osmKey == "place" -> PlaceCategory.DISTRICT
                osmKey in listOf("tourism", "historic", "leisure") -> PlaceCategory.LANDMARK
                else -> PlaceCategory.POI
            }

            val subtitle = if (street.isNotEmpty()) "$street, $district" else district

            list.add(
                SearchPlace(
                    id = "photon_$i",
                    nameEn = name,
                    nameAr = name,
                    district = subtitle,
                    category = category,
                    coordinates = LatLong(lat, lon),
                    sourceProvider = "OpenStreetMap (Free)"
                )
            )
        }
        return list
    }

    private fun searchGooglePlaces(query: String, apiKey: String): List<SearchPlace> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Try Google Places TextSearch with Baghdad location bias (lat 33.3152, lon 44.3661, radius 40km)
        val url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=$encoded&location=33.3152,44.3661&radius=40000&language=ar&key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchPlace>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val name = item.optString("name")
            val formattedAddress = item.optString("formatted_address")
            val geometry = item.optJSONObject("geometry") ?: continue
            val location = geometry.optJSONObject("location") ?: continue
            val lat = location.getDouble("lat")
            val lng = location.getDouble("lng")

            val types = item.optJSONArray("types")
            var category = PlaceCategory.POI
            if (types != null) {
                val typesStr = types.toString()
                category = when {
                    typesStr.contains("hospital") || typesStr.contains("doctor") || typesStr.contains("pharmacy") -> PlaceCategory.HOSPITAL
                    typesStr.contains("shopping_mall") || typesStr.contains("store") -> PlaceCategory.MALL
                    typesStr.contains("university") || typesStr.contains("school") -> PlaceCategory.UNIVERSITY
                    typesStr.contains("restaurant") || typesStr.contains("cafe") -> PlaceCategory.RESTAURANT
                    typesStr.contains("sublocality") || typesStr.contains("neighborhood") -> PlaceCategory.DISTRICT
                    typesStr.contains("tourist_attraction") || typesStr.contains("place_of_worship") -> PlaceCategory.LANDMARK
                    else -> PlaceCategory.POI
                }
            }

            list.add(
                SearchPlace(
                    id = "google_$i",
                    nameEn = name,
                    nameAr = name,
                    district = formattedAddress.replace(", Iraq", "").replace(", العراق", ""),
                    category = category,
                    coordinates = LatLong(lat, lng),
                    sourceProvider = "Google Places"
                )
            )
        }
        return list
    }

    private fun searchLocalOffline(query: String): List<SearchPlace> {
        val lower = query.lowercase(Locale.ROOT)

        val memoryMatches = memorySearchCache.values.filter {
            it.nameEn.lowercase(Locale.ROOT).contains(lower) ||
            it.nameAr.contains(lower) ||
            it.district.lowercase(Locale.ROOT).contains(lower)
        }

        val offlineMatches = offlinePlaces.filter { place ->
            place.nameEn.lowercase(Locale.ROOT).contains(lower) ||
            place.nameAr.contains(lower) ||
            place.district.lowercase(Locale.ROOT).contains(lower) ||
            place.category.displayName.lowercase(Locale.ROOT).contains(lower)
        }

        val combined = (memoryMatches + offlineMatches).distinctBy { "${it.coordinates.latitude},${it.coordinates.longitude}" }
        return combined
    }

    private fun cacheResults(results: List<SearchPlace>) {
        results.forEach {
            memorySearchCache[it.nameEn.lowercase(Locale.ROOT)] = it
        }
    }

    private fun sortAndCalculateDistances(list: List<SearchPlace>, userLocation: LatLong?) {
        if (userLocation != null) {
            list.forEach {
                it.distanceMeters = GeoUtils.calculateDistance(userLocation, it.coordinates)
            }
        }
    }
}
