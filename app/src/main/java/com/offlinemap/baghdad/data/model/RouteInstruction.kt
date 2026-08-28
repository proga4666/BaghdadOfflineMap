package com.offlinemap.baghdad.data.model

import org.mapsforge.core.model.LatLong

data class LaneInfo(
    val direction: String, // "left", "through", "right", "slight_left", "slight_right"
    val isActive: Boolean
)

data class RouteInstruction(
    val text: String,
    val distanceMeters: Double,
    val timeMillis: Long,
    val sign: Int,
    val streetName: String,
    val location: LatLong? = null,
    val lanes: List<LaneInfo>? = null
) {
    enum class TurnType {
        STRAIGHT,
        SLIGHT_LEFT,
        LEFT,
        SHARP_LEFT,
        SLIGHT_RIGHT,
        RIGHT,
        SHARP_RIGHT,
        UTURN,
        REACHED_VIA,
        FINISH,
        UNKNOWN
    }

    val turnType: TurnType
        get() {
            val lower = text.lowercase()
            return when {
                sign == 1 || lower.contains("keep right") || lower.contains("slight right") || lower.contains("الزم اليمين") || lower.contains("يمينا قليلا") -> TurnType.SLIGHT_RIGHT
                sign == -1 || lower.contains("keep left") || lower.contains("slight left") || lower.contains("الزم اليسار") || lower.contains("يسارا قليلا") -> TurnType.SLIGHT_LEFT
                sign == 2 || sign == 3 || lower.contains("turn right") || lower.contains("sharp right") || lower.contains("انعطف يمين") -> TurnType.RIGHT
                sign == -2 || sign == -3 || lower.contains("turn left") || lower.contains("sharp left") || lower.contains("انعطف يسار") -> TurnType.LEFT
                sign == 4 || lower.contains("arrive") || lower.contains("destination") || lower.contains("وصلت") -> TurnType.FINISH
                sign == -6 || sign == 6 || lower.contains("u-turn") || lower.contains("دوران") || lower.contains("استدر") -> TurnType.UTURN
                sign == 5 -> TurnType.REACHED_VIA
                else -> TurnType.STRAIGHT
            }
        }
}
