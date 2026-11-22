package com.tomforge.tomlink

import android.content.Context
import java.text.NumberFormat

internal data class TravelDuration(val vehicleMs: Long = 0, val bicycleMs: Long = 0) {
    val totalMs: Long get() = vehicleMs + bicycleMs
}

internal data class TravelSpeeds(val vehicleKph: Int, val bicycleKph: Int) {
    fun kilometers(travel: TravelDuration): Double =
        estimatedKm(travel.vehicleMs, vehicleKph) + estimatedKm(travel.bicycleMs, bicycleKph)

    fun averageKph(travel: TravelDuration): Double =
        if (travel.totalMs == 0L) 0.0 else kilometers(travel) / (travel.totalMs / 3_600_000.0)
}

internal fun travelSpeeds(context: Context): TravelSpeeds {
    val settings = context.getSharedPreferences("transport", Context.MODE_PRIVATE)
    return TravelSpeeds(settings.getInt("vehicleKph", 30), settings.getInt("bicycleKph", 15))
}

internal fun formatDistance(km: Double, format: NumberFormat): String = when {
    km == 0.0 -> "0 km"
    km < 0.05 -> "<0.1 km"
    else -> "約 ${format.format(km)} km"
}

internal fun formatTravelDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms == 0L -> "0 分"
        minutes == 0L -> "<1 分"
        minutes < 60 -> "$minutes 分"
        else -> "${minutes / 60} 小時 ${minutes % 60} 分"
    }
}

internal fun estimatedKm(durationMs: Long, speedKph: Int): Double =
    durationMs / 3_600_000.0 * speedKph
