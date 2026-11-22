package com.tomforge.tomlink

internal data class TravelDuration(val vehicleMs: Long = 0, val bicycleMs: Long = 0) {
    val totalMs: Long get() = vehicleMs + bicycleMs
}

internal data class TravelSpeeds(val vehicleKph: Int, val bicycleKph: Int) {
    fun kilometers(travel: TravelDuration): Double =
        estimatedKm(travel.vehicleMs, vehicleKph) + estimatedKm(travel.bicycleMs, bicycleKph)
}

internal fun estimatedKm(durationMs: Long, speedKph: Int): Double =
    durationMs / 3_600_000.0 * speedKph
