package com.offlinemap.baghdad.data.model

import org.mapsforge.core.model.LatLong

data class RouteInstruction(
    val text: String,
    val distanceMeters: Double,
    val timeMillis: Long,
    val sign: Int,
    val streetName: String,
    val location: LatLong? = null
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
        get() = when (sign) {
            0 -> TurnType.STRAIGHT
            -1 -> TurnType.SLIGHT_LEFT
            -2 -> TurnType.LEFT
            -3 -> TurnType.SHARP_LEFT
            1 -> TurnType.SLIGHT_RIGHT
            2 -> TurnType.RIGHT
            3 -> TurnType.SHARP_RIGHT
            4 -> TurnType.FINISH
            5 -> TurnType.REACHED_VIA
            -6, 6 -> TurnType.UTURN
            else -> TurnType.STRAIGHT
        }
}
