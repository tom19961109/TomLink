package com.tomforge.tomlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val settings by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val sync by lazy { TomPetSync(applicationContext) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val hasCounter by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null }
    private val hasDetector by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var account: TextView
    private lateinit var toggle: Button
    private lateinit var send: Button
    private lateinit var disconnect: Button
    private lateinit var export: Button
    private var email: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (24 * resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                layout.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            }
            insets
        }
        layout.addView(TextView(this).apply { text = "TomLink"; textSize = 32f })
        layout.addView(TextView(this).apply {
            text = "首次授權後會自動計步，切換 App 或鎖屏時也可記錄。步數先存在此裝置。"
        })
        status = TextView(this)
        layout.addView(status)
        toggle = Button(this).apply {
            setOnClickListener {
                if (StepService.status.running) {
                    settings.edit().putBoolean(ENABLED, false).apply()
                    stopService(Intent(this@MainActivity, StepService::class.java))
                } else {
                    settings.edit().putBoolean(ENABLED, true).apply()
                    startCounting(true)
                }
            }
        }
        layout.addView(toggle)
        layout.addView(Switch(this).apply {
            text = "即時步數偵測"
            isEnabled = hasCounter && hasDetector
            isChecked = hasDetector && (!hasCounter || settings.getBoolean(FAST, false))
            setOnCheckedChangeListener { _, checked ->
                settings.edit().putBoolean(FAST, checked).apply()
                if (StepService.status.running) startCounting(false)
            }
        })
        layout.addView(TextView(this).apply { text = "平常使用系統計步；想即時看步數時可開啟逐步偵測。" })
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
        account = TextView(this)
        layout.addView(account)
        send = Button(this).apply {
            text = "同步至 TomPet"
            setOnClickListener { sendSteps() }
        }
        layout.addView(send)
        disconnect = Button(this).apply {
            text = "解除 Gmail 連結"
            setOnClickListener {
                runWork({ sync.disconnect() }) {
                    email = null
                    notifyUser("已解除 Gmail 連結")
                }
            }
        }
        layout.addView(disconnect)
        scroll.addView(layout)
        setContentView(scroll)
        renderSteps(StepService.status)
        renderSync()
    }

    override fun onResume() {
        super.onResume()
        StepService.listener = ::renderSteps
        renderSteps(StepService.status)
        if (settings.getBoolean(ENABLED, true) && !StepService.status.running && StepService.status.error == null) {
            startCounting(!settings.getBoolean("askedMotion", false))
        }
        if (sync.hasDevice && !busy) runWork({ sync.email() }) { email = it }
    }

    override fun onPause() {
        StepService.listener = null
        super.onPause()
    }

    private fun startCounting(askPermission: Boolean) {
        if (!hasCounter && !hasDetector) return
        if (!hasMotionPermission(this)) {
            if (askPermission && Build.VERSION.SDK_INT >= 29) {
                settings.edit().putBoolean("askedMotion", true).apply()
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            }
            return
        }
        try {
            startForegroundService(Intent(this, StepService::class.java))
            if (Build.VERSION.SDK_INT >= 33 && !settings.getBoolean("askedNotifications", false)) {
                settings.edit().putBoolean("askedNotifications", true).apply()
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                }
            }
        } catch (_: IllegalStateException) {
            notifyUser("請回到 TomLink 開始計步")
        } catch (_: SecurityException) {
            notifyUser("請允許身體活動權限")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 1) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCounting(false)
        else renderSteps(StepService.status)
    }

    private fun sendSteps() {
        StepService.flushPending?.invoke()
        runWork({
            val address = sync.email()
            if (address == null) Pair(null, sync.pair())
            else Pair(address, StepStore(applicationContext).use { sync.upload(it, address) }.toString())
        }) { (address, result) ->
            email = address
            if (address == null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)))
                } catch (_: android.content.ActivityNotFoundException) {
                    notifyUser("請安裝瀏覽器後再連結 Gmail")
                }
            }
            else notifyUser(if (result == "0") "目前沒有新的步數" else "已同步 $result 筆步數至 TomPet")
        }
    }

    private fun renderSteps(state: StepService.Status) {
        status.text = when {
            !hasCounter && !hasDetector -> "此裝置沒有系統計步感測器。"
            !hasMotionPermission(this) -> "需要身體活動權限才能計步。"
            state.error != null -> state.error
            state.running -> "背景計步中｜本次 ${state.steps} 步"
            else -> "計步已暫停｜本次 ${state.steps} 步"
        }
        toggle.isEnabled = hasCounter || hasDetector
        toggle.text = if (state.running) "暫停計步" else "開始計步"
    }

    private fun renderSync() {
        account.text = email?.let { "同步帳號：$it" }
            ?: sync.code?.let { "回到瀏覽器確認裝置代碼 $it" }
            ?: "請用和 TomPet 相同的 Gmail 登入。"
        send.isEnabled = !busy
        export.isEnabled = !busy
        disconnect.isEnabled = !busy && sync.hasDevice
    }

    private fun <T> runWork(work: () -> T, complete: (T) -> Unit) {
        if (busy) return
        busy = true
        renderSync()
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess(complete).onFailure {
                    if (!sync.hasDevice) email = null
                    notifyUser(it.message ?: "操作失敗，請稍後再試")
                }
                renderSync()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        StepService.flushPending?.invoke()
        runWork({
            val stream = checkNotNull(contentResolver.openOutputStream(uri, "wt"))
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                StepStore(applicationContext).use { store -> writer.write(store.export().toString()) }
            }
        }) { notifyUser("已匯出步數") }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
