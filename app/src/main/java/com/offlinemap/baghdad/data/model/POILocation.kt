package com.offlinemap.baghdad.data.model

import org.mapsforge.core.model.LatLong

data class POILocation(
    val name: String,
    val description: String,
    val latLong: LatLong
) {
    companion object {
        // Pre-defined popular landmarks in Baghdad for instant offline testing
        val BAGHDAD_LANDMARKS = listOf(
            POILocation(
                name = "Al-Mansour (14th of Ramadan)",
                description = "Mansour Commercial Center",
                latLong = LatLong(33.3152, 44.3540)
            ),
            POILocation(
                name = "Karrada (Kahramana Square)",
                description = "Kahramana & 40 Thieves Monument",
                latLong = LatLong(33.3006, 44.4282)
            ),
            POILocation(
                name = "Al-Jadriya (University of Baghdad)",
                description = "Main University Campus / Tigris Peninsula",
                latLong = LatLong(33.2758, 44.3802)
            ),
            POILocation(
                name = "Bab Al-Sharqi (Tahrir Square)",
                description = "Freedom Monument / City Center",
                latLong = LatLong(33.3283, 44.4103)
            ),
            POILocation(
                name = "Al-Kadhimiya Shrine",
                description = "Holy Shrine of Imam Al-Kadhim",
                latLong = LatLong(33.3802, 44.3418)
            ),
            POILocation(
                name = "Al-Mutanabbi Street",
                description = "Historic Books & Culture Street",
                latLong = LatLong(33.3385, 44.3888)
            ),
            POILocation(
                name = "Baghdad International Airport (BGW)",
                description = "Airport Terminal & Highway",
                latLong = LatLong(33.2625, 44.2344)
            ),
            POILocation(
                name = "Zayouna (Palestine Street)",
                description = "Commercial District",
                latLong = LatLong(33.3325, 44.4530)
            ),
            POILocation(
                name = "Al-A'amiriya",
                description = "Western Baghdad District",
                latLong = LatLong(33.3032, 44.2882)
            ),
            POILocation(
                name = "Al-Saydiyah",
                description = "South-West Baghdad",
                latLong = LatLong(33.2428, 44.3312)
            )
        )
    }
}
