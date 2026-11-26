package com.tomforge.tomlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

internal data class ActiveTravel(val startElapsedMs: Long, val startWallMs: Long, val endWallMs: Long, val bicycle: Boolean)

internal data class GpsTotals(val durationMs: Long = 0, val speedSum: Double = 0.0) {
    val averageKph: Double? get() = if (durationMs > 0L) speedSum / durationMs else null
}

internal class GpsTravel(private val context: Context, private val showSpeed: (Double?) -> Unit) : LocationListener {
    private data class Reading(val elapsedMs: Long, val wallMs: Long, val kph: Double, val active: ActiveTravel?)
    private val manager = context.getSystemService(LocationManager::class.java)
    private val store = context.getSharedPreferences("gps_travel", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val stale = Runnable { clearReading() }
    private var startedAt: Long? = null
    private var reading: Reading? = null

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (startedAt != null || !hasPermission()) return
        val gps = manager ?: return
        val now = SystemClock.elapsedRealtime()
        try {
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            startedAt = now
        } catch (_: IllegalArgumentException) {
            clearReading()
        } catch (_: SecurityException) {
            clearReading()
        }
    }

    fun stop() {
        if (startedAt != null) manager?.removeUpdates(this)
        startedAt = null
        clearReading()
    }

    fun totals(day: LocalDate): GpsTotals {
        val snapshot = store.all
        return GpsTotals(
            snapshot["time:$day"] as? Long ?: 0L,
            Double.fromBits(snapshot["sum:$day"] as? Long ?: 0L),
        )
    }

    override fun onLocationChanged(location: Location) {
        val started = startedAt ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = location.elapsedRealtimeNanos / 1_000_000L
        val age = now - elapsed
        if (elapsed < started || age !in 0L until 5_000L || elapsed <= (reading?.elapsedMs ?: -1L)) return
        if (!location.hasSpeed() || location.speed !in 0f..150f) return
        if (location.hasSpeedAccuracy() && location.speedAccuracyMetersPerSecond !in 0f..3f) return
        if (!location.hasSpeedAccuracy() && (!location.hasAccuracy() || location.accuracy !in 0f..30f)) return

        val speed = if (location.speed < 0.5f) 0.0 else location.speed * 3.6
        val point = Reading(elapsed, System.currentTimeMillis() - age, speed, TransportReceiver.activeTravel(context, now))
        reading?.let { previous -> record(previous, point) }
        reading = point
        handler.removeCallbacks(stale)
        handler.postDelayed(stale, 5_000L - age)
        showSpeed(speed)
    }

    override fun onProviderDisabled(provider: String) = clearReading()

    private fun clearReading() {
        handler.removeCallbacks(stale)
        reading = null
        showSpeed(null)
    }

    private fun record(previous: Reading, next: Reading) {
        val active = next.active ?: return
        val elapsed = next.elapsedMs - previous.elapsedMs
        val wallDuration = next.wallMs - previous.wallMs
        if (previous.active?.startElapsedMs != active.startElapsedMs || previous.active.bicycle != active.bicycle) return
        if (elapsed !in 1..5_000L || wallDuration <= 0 || abs(elapsed - wallDuration) > 500) return
        if (previous.elapsedMs < active.startElapsedMs) return

        val zone = ZoneId.systemDefault()
        val changes = store.edit()
        var start = previous.wallMs
        while (start < next.wallMs) {
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(boundary, next.wallMs)
            val startFraction = (start - previous.wallMs).toDouble() / wallDuration
            val endFraction = (end - previous.wallMs).toDouble() / wallDuration
            val duration = (elapsed * endFraction).toLong() - (elapsed * startFraction).toLong()
            val mean = previous.kph + (next.kph - previous.kph) * (startFraction + endFraction) / 2
            val old = totals(day)
            changes.putLong("time:$day", old.durationMs + duration)
                .putLong("sum:$day", (old.speedSum + duration * mean).toBits())
            start = end
        }
        changes.apply()
    }
}
