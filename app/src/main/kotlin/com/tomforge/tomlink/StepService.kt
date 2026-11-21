package com.tomforge.tomlink

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal const val PREFS = "steps"
internal const val ENABLED = "countingEnabled"
internal const val FAST = "stepDetector"

internal fun hasMotionPermission(context: Context) = Build.VERSION.SDK_INT < 29 ||
    context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

class StepService : Service(), SensorEventListener {
    internal data class Status(val running: Boolean = false, val detector: Boolean = false, val steps: Long = 0, val error: String? = null)

    companion object {
        internal const val STOP = "com.tomforge.tomlink.STOP"
        private const val CHANNEL = "step-counting"
        private const val NOTIFICATION = 10
        private const val BATCH_MS = 30_000L
        internal var status = Status()
            private set
        internal var listener: ((Status) -> Unit)? = null
        internal var flushPending: (() -> Unit)? = null
            private set

        private fun publish(next: Status) {
            status = next
            listener?.invoke(next)
        }
    }

    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val store by lazy { StepStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val flushTask = Runnable { flush() }
    private var activeSensor: Sensor? = null
    private var startNanos = 0L
    private var startMillis = 0L
    private var previousCount: Long? = null
    private var previousTime = 0L
    private var batchStart = 0L
    private var batchEnd = 0L
    private var batchSteps = 0L
    private var total = 0L
    private var failed = false
    private val installationId by lazy {
        val source = preferences.getString("sourceId", null) ?: ("android-step-counter:" + UUID.randomUUID()).also {
            preferences.edit().putString("sourceId", it).apply()
        }
        source.substringAfter(':')
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            preferences.edit().putBoolean(ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!preferences.getBoolean(ENABLED, true) || !hasMotionPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION, notification())
            }
        } catch (_: SecurityException) {
            publish(Status(error = "無法使用背景計步，請檢查身體活動權限。"))
            stopSelf()
            return START_NOT_STICKY
        }

        val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val preferred = if (preferences.getBoolean(FAST, false)) detector ?: counter else counter ?: detector
        if (activeSensor != null && preferred == activeSensor) return START_STICKY
        flush()
        if (failed) return START_NOT_STICKY
        sensors.unregisterListener(this)
        activeSensor = preferred
        startMillis = System.currentTimeMillis()
        startNanos = SystemClock.elapsedRealtimeNanos()
        previousCount = null
        previousTime = startMillis
        if (preferred == null || !sensors.registerListener(this, preferred, SensorManager.SENSOR_DELAY_NORMAL)) {
            activeSensor = null
            publish(Status(error = "此裝置無法啟用計步感測器。"))
            stopSelf()
            return START_NOT_STICKY
        }
        flushPending = { flush() }
        publish(Status(true, preferred.type == Sensor.TYPE_STEP_DETECTOR, total))
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != activeSensor || failed) return
        val time = startMillis + (event.timestamp - startNanos).coerceAtLeast(0L) / 1_000_000
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val count = event.values[0].toLong()
            val baseline = previousCount
            previousCount = count
            if (baseline == null || count <= baseline) {
                previousTime = maxOf(previousTime, time)
                return
            }
            if (time <= previousTime) return
            addSteps(previousTime, time, count - baseline)
        } else {
            if (event.timestamp < startNanos || time <= previousTime) return
            addSteps(maxOf(previousTime, time - 1_000), time, 1)
        }
        previousTime = time
    }

    private fun addSteps(start: Long, end: Long, steps: Long) {
        if (batchSteps > 0 && end - batchStart >= BATCH_MS) flush()
        if (failed) return
        if (batchSteps == 0L) {
            batchStart = start
            handler.postDelayed(flushTask, BATCH_MS)
        }
        batchEnd = end
        batchSteps += steps
        total += steps
        publish(status.copy(steps = total))
    }

    private fun flush() {
        handler.removeCallbacks(flushTask)
        val sensor = activeSensor ?: return
        if (batchSteps == 0L || failed) return
        val prefix = if (sensor.type == Sensor.TYPE_STEP_DETECTOR) "android-step-detector" else "android-step-counter"
        val record = JSONObject()
            .put("schemaVersion", "1.0")
            .put("recordId", UUID.randomUUID().toString())
            .put("sourceId", "$prefix:$installationId")
            .put("type", "steps")
            .put("measurementKind", "interval-total")
            .put("value", batchSteps)
            .put("unit", "count")
            .put("startTime", Instant.ofEpochMilli(batchStart).toString())
            .put("endTime", Instant.ofEpochMilli(batchEnd).toString())
            .put("recordedAt", Instant.now().toString())
            .put("quality", "observed")
        try {
            store.append(record)
            batchSteps = 0
        } catch (_: android.database.SQLException) {
            failed = true
            publish(Status(steps = total, error = "無法儲存步數，計步已停止。"))
            stopSelf()
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "背景計步", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StepService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TomLink 正在計步")
            .setContentText("步數儲存在這部手機")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止計步", stop).build())
            .build()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensors.unregisterListener(this)
        flush()
        flushPending = null
        store.close()
        publish(status.copy(running = false))
        super.onDestroy()
    }
}
