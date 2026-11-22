package com.tomforge.tomlink

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.core.content.FileProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

private const val UPDATE_PERMISSION = 4

class MainActivity : Activity() {
    private val settings by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val sync by lazy { TomPetSync(applicationContext) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val hasCounter by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null }
    private val hasDetector by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var todaySteps: TextView
    private lateinit var sessionSteps: TextView
    private lateinit var todayTravel: TextView
    private lateinit var averageSpeed: TextView
    private lateinit var todayDistance: TextView
    private val summaryWorker = Executors.newSingleThreadExecutor()
    private val summaryHandler = Handler(Looper.getMainLooper())
    private var summaryActive = false
    private var summaryGeneration = 0
    private val wholeNumber = NumberFormat.getIntegerInstance()
    private val decimalNumber = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val summaryRefresh = object : Runnable {
        override fun run() {
            loadToday()
            summaryHandler.postDelayed(this, 15_000)
        }
    }
    private lateinit var account: TextView
    private lateinit var toggle: Button
    private lateinit var send: Button
    private lateinit var disconnect: Button
    private lateinit var export: Button
    private val updates by lazy { AppUpdates(applicationContext) }
    private lateinit var updateStatus: TextView
    private lateinit var checkUpdate: Button
    private lateinit var installUpdate: Button
    private var latest: AppUpdates.Release? = null
    private var checking = false
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
        fun metric(title: String): TextView {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = title; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = TextView(this).apply { text = "—"; textSize = 16f }
            row.addView(value)
            layout.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 4 })
            return value
        }
        todaySteps = metric("當天總步數")
        sessionSteps = metric("本次步數")
        todayTravel = metric("當天行駛總時間")
        averageSpeed = metric("估算平均時速")
        todayDistance = metric("估算行駛距離")
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("活動月曆" to false, "活動週曆" to true).forEach { (label, weekly) ->
                val button = Button(this@MainActivity).apply {
                    text = label
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, StepCalendarActivity::class.java)
                            .putExtra(StepCalendarActivity.EXTRA_WEEK, weekly))
                    }
                }
                addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        updateStatus = TextView(this).apply { text = "目前版本 ${updates.versionName}" }
        layout.addView(updateStatus)
        checkUpdate = Button(this).apply {
            text = "檢查更新"
            setOnClickListener {
                latest = null
                checkForUpdate()
            }
        }
        installUpdate = Button(this).apply {
            text = "更新"
            setOnClickListener { latest?.let { downloadUpdate(it) } }
        }
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(checkUpdate)
            addView(installUpdate)
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
            text = "自動記錄交通移動"
            isChecked = getSharedPreferences("transport", MODE_PRIVATE).getBoolean("enabled", true)
            setOnCheckedChangeListener { _, checked -> configureTransport(checked) }
        })
        layout.addView(TextView(this).apply { text = "背景偵測搭車或騎單車，只記錄時間與類別。" })
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
            text = "匯出活動 JSON"
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
        summaryActive = true
        summaryRefresh.run()
        StepService.listener = ::renderSteps
        renderSteps(StepService.status)
        if (settings.getBoolean(ENABLED, true) && !StepService.status.running && StepService.status.error == null) {
            startCounting(!settings.getBoolean("askedMotion", false))
        }
        if (hasMotionPermission(this) && getSharedPreferences("transport", MODE_PRIVATE).getBoolean("enabled", true)) configureTransport(true)
        if (sync.hasDevice && !busy) runWork({ sync.email() }) { email = it }
    }

    override fun onPause() {
        summaryActive = false
        summaryGeneration++
        summaryHandler.removeCallbacks(summaryRefresh)
        StepService.listener = null
        super.onPause()
    }

    private fun configureTransport(enabled: Boolean) {
        if (enabled && !hasMotionPermission(this) && Build.VERSION.SDK_INT >= 29) {
            getSharedPreferences("transport", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            return
        }
        TransportReceiver.configure(applicationContext, enabled) { success ->
            if (enabled && !success && !isDestroyed) notifyUser("無法啟用交通偵測，請確認身體活動權限與 Google Play 服務。")
        }
    }

    private fun startCounting(askPermission: Boolean) {
        if (!hasMotionPermission(this)) {
            if (askPermission && Build.VERSION.SDK_INT >= 29) {
                settings.edit().putBoolean("askedMotion", true).apply()
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
            }
            return
        }
        if (!hasCounter && !hasDetector) return
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
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (settings.getBoolean(ENABLED, true)) startCounting(false)
            if (getSharedPreferences("transport", MODE_PRIVATE).getBoolean("enabled", true)) configureTransport(true)
        }
        else renderSteps(StepService.status)
    }

    private fun checkForUpdate() {
        updateStatus.text = "目前版本 ${updates.versionName}｜檢查中…"
        runUpdateWork({ updates.check() }) { release ->
            latest = release
            updateStatus.text = if (release == null) "目前版本 ${updates.versionName}｜已是最新版"
                else "目前版本 ${updates.versionName}｜可更新至 ${release.name}"
        }
    }

    private fun downloadUpdate(release: AppUpdates.Release) {
        updateStatus.text = "正在下載 ${release.name}…"
        runUpdateWork({ updates.download(release) }) {
            updateStatus.text = "已下載 ${release.name}"
            if (packageManager.canRequestPackageInstalls()) openInstaller()
            else {
                try {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")), UPDATE_PERMISSION)
                } catch (_: ActivityNotFoundException) {
                    notifyUser("請在系統設定允許 TomLink 安裝應用程式")
                }
            }
        }
    }

    private fun openInstaller() {
        try {
            updates.validateApk()
            StepService.flushPending?.invoke()
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", updates.apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (error: Exception) {
            notifyUser(error.message ?: "無法開啟安裝畫面")
        }
    }

    private fun <T> runUpdateWork(work: () -> T, complete: (T) -> Unit) {
        checking = true
        renderSync()
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checking = false
                result.onSuccess(complete).onFailure {
                    updateStatus.text = "目前版本 ${updates.versionName}｜${it.message ?: "更新失敗"}"
                }
                renderSync()
            }
        }
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
            else notifyUser(if (result == "0") "目前沒有新的活動紀錄" else "已同步 $result 天的活動至 TomPet")
        }
    }

    private fun loadToday() {
        if (!summaryActive) return
        val generation = ++summaryGeneration
        StepService.flushPending?.invoke()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val tomorrow = today.plusDays(1)
        val currentSpeeds = travelSpeeds(this)
        summaryWorker.execute {
            val result = runCatching {
                StepStore(applicationContext).use { store ->
                    val steps = store.stepsByDay(today, tomorrow, zone).getValue(today)
                    val travel = store.transportByDay(today, tomorrow, zone)[today] ?: TravelDuration()
                    steps to travel
                }
            }
            runOnUiThread {
                if (!summaryActive || generation != summaryGeneration) return@runOnUiThread
                result.onSuccess { (steps, travel) ->
                    todaySteps.text = "${wholeNumber.format(steps)} 步"
                    todayTravel.text = formatTravelDuration(travel.totalMs)
                    averageSpeed.text = if (travel.totalMs > 0) "約 ${decimalNumber.format(currentSpeeds.averageKph(travel))} km/h" else "—"
                    todayDistance.text = formatDistance(currentSpeeds.kilometers(travel), decimalNumber)
                }
            }
        }
    }

    private fun renderSteps(state: StepService.Status) {
        sessionSteps.text = "${wholeNumber.format(state.steps)} 步"
        status.text = when {
            !hasCounter && !hasDetector -> "此裝置沒有系統計步感測器。"
            !hasMotionPermission(this) -> "需要身體活動權限才能計步。"
            state.error != null -> state.error
            state.running -> "背景計步中"
            else -> "計步已暫停"
        }
        toggle.isEnabled = hasCounter || hasDetector
        toggle.text = if (state.running) "暫停計步" else "開始計步"
    }

    private fun renderSync() {
        account.text = listOfNotNull(
            email?.let { "同步帳號：$it" },
            sync.code?.let { "裝置代碼：$it" },
        ).joinToString("\n").ifEmpty { "請用和 TomPet 相同的 Gmail 登入。" }
        checkUpdate.isEnabled = !checking
        installUpdate.isEnabled = !checking
        installUpdate.visibility = if (latest != null) View.VISIBLE else View.GONE
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
        if (requestCode == UPDATE_PERMISSION) {
            if (packageManager.canRequestPackageInstalls()) openInstaller()
            else notifyUser("允許 TomLink 安裝應用程式後即可更新")
            return
        }
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
        summaryWorker.shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

