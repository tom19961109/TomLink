package com.tomforge.tomlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class TransportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents ?: return
        val pending = goAsync()
        executor.execute {
            try {
                val state = context.getSharedPreferences("transport", Context.MODE_PRIVATE)
                if (!state.getBoolean("enabled", true)) return@execute
                val now = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val bootEpoch = now - elapsed
                val oldBoot = state.getLong("boot", 0)
                val sameBoot = kotlin.math.abs(oldBoot - bootEpoch) < 120_000
                val session = if (sameBoot) oldBoot else bootEpoch
                var active = if (sameBoot) state.getInt("active", -1) else -1
                var start = if (active == -1) 0 else state.getLong("start", 0)
                var last = if (sameBoot) state.getLong("last", -1) else -1
                val source = state.getString("source", null) ?: UUID.randomUUID().toString().also {
                    check(state.edit().putString("source", it).commit())
                }
                StepStore(context).use { store ->
                    for (event in events.sortedBy { it.elapsedRealTimeNanos }) {
                        val eventNanos = event.elapsedRealTimeNanos
                        val time = eventNanos / 1_000_000
                        if (eventNanos <= last || time > elapsed) continue
                        if (mode(event.activityType) == null) continue
                        val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                        if (active != -1 && (entering && active != event.activityType || !entering && active == event.activityType)) {
                            val duration = time - start
                            if (duration in 1..86_400_000L) {
                                val endWall = bootEpoch + time
                                store.append(JSONObject()
                                    .put("type", "transport")
                                    .put("schemaVersion", "1.0")
                                    .put("sourceId", source)
                                    .put("recordId", "$session:$active:$start:$time")
                                    .put("mode", mode(active))
                                    .put("startTime", Instant.ofEpochMilli(endWall - duration).toString())
                                    .put("endTime", Instant.ofEpochMilli(endWall).toString()))
                            }
                            active = -1
                        }
                        if (entering && active == -1) {
                            active = event.activityType
                            start = time
                        }
                        last = eventNanos
                    }
                }
                state.edit().putLong("boot", session)
                    .putInt("active", active).putLong("start", start).putLong("last", last).commit()
            } catch (error: Exception) {
                android.util.Log.w("TomLink", "Unable to save transport activity", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE)
        private fun mode(type: Int) = when (type) {
            DetectedActivity.IN_VEHICLE -> "vehicle"
            DetectedActivity.ON_BICYCLE -> "bicycle"
            else -> null
        }

        fun configure(context: Context, enabled: Boolean, onResult: (Boolean) -> Unit) {
            val state = context.getSharedPreferences("transport", Context.MODE_PRIVATE)
            state.edit().putBoolean("enabled", enabled).apply()
            if (!enabled) state.edit().remove("active").remove("start").apply()
            val client = ActivityRecognition.getClient(context)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, 41, Intent(context, TransportReceiver::class.java), flags)
            try {
                val task = if (enabled) {
                    val transitions = types.flatMap { type ->
                        listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                            .map { change -> ActivityTransition.Builder().setActivityType(type).setActivityTransition(change).build() }
                    }
                    client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), callback)
                } else client.removeActivityTransitionUpdates(callback)
                task.addOnCompleteListener { onResult(it.isSuccessful) }
            } catch (_: SecurityException) {
                onResult(false)
            }
        }
    }
}
