package com.tomforge.tomlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class MainActivity : Activity(), SensorEventListener {
    private val store by lazy { getSharedPreferences("steps", MODE_PRIVATE) }
    private val manager by lazy { getSystemService(SensorManager::class.java) }
    private val sensor by lazy { manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var export: Button
    private val records by lazy { StepStore(this) }
    private var recordCount = 0L
    private val sourceId by lazy {
        store.getString("sourceId", null) ?: ("android-step-counter:" + UUID.randomUUID()).also {
            store.edit().putString("sourceId", it).commit()
        }
    }
    private var listening = false
    private var previousCount = 0L
    private var previousTime = 0L
    private var startedAt = 0L
    private var sessionSteps = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                } else {
                    view.setPadding(pad, pad, pad, pad)
                }
                insets
            }
        }
        layout.addView(TextView(this).apply { text = "TomLink"; textSize = 32f })
        layout.addView(TextView(this).apply {
            text = "在畫面開啟時記錄步數。離開畫面或鎖定手機會結束本次計步。紀錄存在此裝置，可自行匯出。"
        })
        status = TextView(this)
        layout.addView(status)
        toggle = Button(this).apply {
            setOnClickListener { if (listening) stopCounting() else requestCounting() }
        }
        layout.addView(toggle)
        export = Button(this).apply {
            text = "匯出步數 JSON"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "tomlink-steps.json")
                }, 2)
            }
        }
        layout.addView(export)
        recordCount = records.count()
        setContentView(layout)
        render()
    }

    private fun requestCounting() {
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            return
        }
        val counter = sensor ?: return
        previousTime = 0
        sessionSteps = 0
        startedAt = SystemClock.elapsedRealtimeNanos()
        listening = manager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL)
        if (!listening) notifyUser("無法啟動計步感測器")
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 1) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCounting()
        else notifyUser("需要身體活動權限才能計步")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening || event.timestamp < startedAt) return
        val count = event.values[0].toLong()
        val now = System.currentTimeMillis()
        val time = now - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val delta = count - previousCount
        if (previousTime > 0 && time > previousTime && delta > 0) {
            val record = JSONObject()
                .put("schemaVersion", "1.0")
                .put("recordId", UUID.randomUUID().toString())
                .put("sourceId", sourceId)
                .put("type", "steps")
                .put("measurementKind", "interval-total")
                .put("value", delta)
                .put("unit", "count")
                .put("startTime", Instant.ofEpochMilli(previousTime).toString())
                .put("endTime", Instant.ofEpochMilli(time).toString())
                .put("recordedAt", Instant.ofEpochMilli(now).toString())
                .put("quality", "observed")
            try {
                records.append(record)
                recordCount++
            } catch (_: android.database.SQLException) {
                stopCounting()
                notifyUser("無法儲存紀錄，已停止計步")
                return
            }
            sessionSteps += delta
        }
        previousCount = count
        previousTime = time
        render()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        stopCounting()
        super.onPause()
    }

    private fun stopCounting() {
        manager.unregisterListener(this)
        listening = false
        render()
    }

    private fun render() {
        status.text = when {
            sensor == null -> "此裝置沒有計步感測器。"
            listening && previousTime == 0L -> "等待第一筆感測器資料…"
            else -> "本次計步：${sessionSteps} 步｜已儲存 ${recordCount} 筆區間"
        }
        toggle.isEnabled = sensor != null
        toggle.text = if (listening) "停止計步" else "開始計步"
        export.isEnabled = !listening && recordCount > 0
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val stream = checkNotNull(contentResolver.openOutputStream(uri, "wt"))
            stream.bufferedWriter().use { it.write(records.export().toString()) }
            notifyUser("已匯出步數")
        } catch (_: Exception) {
            notifyUser("無法匯出步數")
        }
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
