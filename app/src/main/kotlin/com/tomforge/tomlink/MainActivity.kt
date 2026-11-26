package com.tomforge.tomlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.core.view.ViewCompat
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

private const val UPDATE_PERMISSION = 4
private const val GPS_PERMISSION = 5

class MainActivity : Activity() {
    private val settings by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val sync by lazy { TomPetSync(applicationContext) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val hasCounter by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null }
    private val hasDetector by lazy { sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var controlsDialog: AlertDialog
    private lateinit var goalProgress: ProgressBar
    private lateinit var goalText: TextView
    private lateinit var todaySteps: TextView
    private lateinit var countingSetting: Switch
    private lateinit var gpsSetting: Switch
    private lateinit var liveSpeedCell: LinearLayout
    private lateinit var liveSpeed: TextView
    private lateinit var travelSource: TextView
    private val gps by lazy {
        GpsTravel(this) { speed ->
            liveSpeedCell.visibility = if (speed == null) View.INVISIBLE else View.VISIBLE
            liveSpeed.text = speed?.let { "${decimalNumber.format(it)} km/h" }.orEmpty()
        }
    }
    private lateinit var todayTravel: TextView
    private lateinit var averageSpeed: TextView
    private lateinit var todayDistance: TextView
    private val summaryWorker = Executors.newSingleThreadExecutor()
    private val summaryHandler = Handler(Looper.getMainLooper())
    private var summaryActive = false
    private var summaryGeneration = 0
    private var stepRefreshQueued = false
    private val stepRefresh = Runnable {
        stepRefreshQueued = false
        loadToday()
    }
    private val wholeNumber = NumberFormat.getIntegerInstance()
    private val decimalNumber = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val summaryRefresh = object : Runnable {
        override fun run() {
            loadToday()
            summaryHandler.postDelayed(this, if (settings.getBoolean("gpsSpeed", false)) 5_000L else 15_000L)
        }
    }
    private lateinit var account: TextView
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
        val page = column()
        val title = label("TomLink", 30, true)
        ViewCompat.setAccessibilityHeading(title, true)
        val controls = action("設定與更新") { controlsDialog.show() }
        page.block(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(controls)
        })
        page.block(activitySummary(), 12)
        page.block(travelSummary(), 12)
        page.block(LinearLayout(this).apply {
            fun addAction(button: Button, gap: Int) {
                addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(gap) })
            }
            addAction(calendarAction("週曆", true), 0)
            addAction(calendarAction("月曆", false), 6)
            send = action("同步", primary = true) { sendSteps() }
            addAction(send, 6)
        }, 10)
        controlsDialog = createControls()
        setContentView(pageScroll(page))
        renderSync()
        if (savedInstanceState?.getBoolean("controlsOpen") == true) controlsDialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("controlsOpen", controlsDialog.isShowing)
        super.onSaveInstanceState(outState)
    }

    private fun heading(text: String) = label(text, 18, true).apply { ViewCompat.setAccessibilityHeading(this, true) }

    private fun activitySummary() = card(LinkColors.accent).apply {
        block(label("今日步數", 16, true, LinkColors.onAccent))
        todaySteps = label("—", 44, true, LinkColors.onAccent).apply {
            fontFeatureSettings = "tnum"
        }
        block(todaySteps, 3)
        goalProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(LinkColors.onAccent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF5B8A73.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        block(goalProgress, 12)
        goalText = label("", 13, ink = LinkColors.mutedMint)
        block(goalText, 5)
    }

    private fun travelSummary() = card().apply {
        block(heading("今日移動"))
        fun metric(title: String, row: LinearLayout, gap: Int = 0): Pair<LinearLayout, TextView> {
            val value = label("—", 16, true)
            val cell = column().apply {
                block(label(title, 13, ink = LinkColors.muted))
                block(value, 2)
            }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(gap) })
            return cell to value
        }
        val first = LinearLayout(this@MainActivity)
        todayTravel = metric("行駛時間", first).second
        todayDistance = metric("行駛里程", first, 10).second
        block(first, 8)
        val second = LinearLayout(this@MainActivity)
        averageSpeed = metric("平均時速", second).second
        val current = metric("當前時速", second, 10)
        liveSpeedCell = current.first.apply { visibility = View.INVISIBLE }
        liveSpeed = current.second
        block(second, 8)
        travelSource = label("", 12, ink = LinkColors.muted).apply { visibility = View.GONE }
        block(travelSource, 8)
    }

    private fun calendarAction(title: String, weekly: Boolean) = action(title) {
        startActivity(Intent(this, StepCalendarActivity::class.java).putExtra(StepCalendarActivity.EXTRA_WEEK, weekly))
    }

    private fun createControls(): AlertDialog {
        val content = column().apply { setPadding(dp(22), dp(4), dp(22), dp(8)) }
        content.block(heading("活動記錄"))
        content.block(Switch(this).apply {
            text = "自動記錄交通移動"
            minHeight = dp(48)
            isChecked = getSharedPreferences("transport", MODE_PRIVATE).getBoolean("enabled", true)
            setOnCheckedChangeListener { _, checked -> configureTransport(checked) }
        })
        content.block(label("記錄搭車與單車的時間及類別。", 13, ink = LinkColors.muted))
        countingSetting = Switch(this).apply {
            text = "即時計步"
            minHeight = dp(48)
            isEnabled = hasCounter || hasDetector
            isChecked = settings.getBoolean(ENABLED, true)
            setOnClickListener {
                settings.edit().putBoolean(ENABLED, isChecked).apply()
                if (isChecked) startCounting(true)
                else stopService(Intent(this@MainActivity, StepService::class.java))
            }
        }
        content.block(countingSetting, 4)
        gpsSetting = Switch(this).apply {
            text = "GPS 時速"
            minHeight = dp(48)
            isChecked = settings.getBoolean("gpsSpeed", false) && gps.hasPermission()
            setOnClickListener {
                if (isChecked && !gps.hasPermission()) {
                    isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), GPS_PERMISSION)
                } else {
                    settings.edit().putBoolean("gpsSpeed", isChecked).apply()
                    updateGps()
                }
            }
        }
        content.block(gpsSetting, 4)
        content.block(label("開啟 App 時量測。手機只保存速度與時間的統計。", 13, ink = LinkColors.muted))
        content.block(heading("資料與連結"), 18)
        account = label("", 14, ink = LinkColors.muted)
        content.block(account, 5)
        export = action("匯出活動 JSON") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "tomlink-steps.json")
            }, 2)
        }
        content.block(export, 8)
        disconnect = action("解除 Gmail 連結") {
            runWork({ sync.disconnect() }) {
                email = null
                notifyUser("已解除 Gmail 連結")
            }
        }
        content.block(disconnect, 6)
        content.block(heading("版本更新"), 18)
        updateStatus = label("目前版本 ${updates.versionName}", 14, ink = LinkColors.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.block(updateStatus, 5)
        checkUpdate = action("檢查更新") {
            latest = null
            checkForUpdate()
        }
        content.block(checkUpdate, 8)
        installUpdate = action("下載並安裝新版", primary = true) { latest?.let { downloadUpdate(it) } }
        content.block(installUpdate, 6)
        val scroll = ScrollView(this).apply { addView(content) }
        return AlertDialog.Builder(this).setTitle("設定與更新").setView(scroll)
            .setPositiveButton("完成", null).create()
    }
    override fun onResume() {
        super.onResume()
        summaryActive = true
        summaryRefresh.run()
        StepService.listener = { onStepUpdate() }
        countingSetting.isChecked = settings.getBoolean(ENABLED, true)
        updateGps()
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
        summaryHandler.removeCallbacks(stepRefresh)
        stepRefreshQueued = false
        gps.stop()
        StepService.listener = null
        super.onPause()
    }

    private fun updateGps() {
        val enabled = settings.getBoolean("gpsSpeed", false) && gps.hasPermission()
        gpsSetting.isChecked = enabled
        if (summaryActive && enabled) gps.start() else gps.stop()
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
        if (requestCode == GPS_PERMISSION) {
            val granted = gps.hasPermission()
            settings.edit().putBoolean("gpsSpeed", granted).apply()
            updateGps()
            if (!granted) notifyUser("開啟 GPS 時速需要精確定位權限")
            return
        }
        if (requestCode != 1) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (settings.getBoolean(ENABLED, true)) startCounting(false)
            if (getSharedPreferences("transport", MODE_PRIVATE).getBoolean("enabled", true)) configureTransport(true)
        }
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
        val active = TransportReceiver.activeTravel(this)
        summaryWorker.execute {
            val result = runCatching {
                StepStore(applicationContext).use { store ->
                    val steps = store.stepsByDay(today, tomorrow, zone).getValue(today)
                    val travel = store.transportByDay(today, tomorrow, zone, active = active)[today] ?: TravelDuration()
                    steps to travel
                }
            }
            runOnUiThread {
                if (!summaryActive || generation != summaryGeneration) return@runOnUiThread
                result.onSuccess { (steps, travel) ->
                    todaySteps.text = wholeNumber.format(steps)
                    val goal = getSharedPreferences("activity_goals", MODE_PRIVATE).getLong("dailySteps", 8000).coerceAtLeast(1)
                    goalProgress.progress = ((steps.toDouble() / goal).coerceIn(0.0, 1.0) * goalProgress.max).toInt()
                    goalText.text = "每日目標 ${wholeNumber.format(goal)} 步"
                    val measured = gps.totals(today)
                    val measuredSpeed = measured.averageKph
                    averageSpeed.text = when {
                        measuredSpeed != null -> "${decimalNumber.format(measuredSpeed)} km/h"
                        travel.totalMs > 0 -> "約 ${decimalNumber.format(currentSpeeds.averageKph(travel))} km/h"
                        else -> "—"
                    }
                    val totalMs = maxOf(travel.totalMs, measured.durationMs)
                    val km = measuredSpeed?.let { estimatedKm(totalMs, it) } ?: currentSpeeds.kilometers(travel)
                    todayTravel.text = formatTravelDuration(totalMs)
                    todayDistance.text = if (measuredSpeed != null && totalMs == measured.durationMs)
                        "${decimalNumber.format(km)} km" else formatDistance(km, decimalNumber)
                    travelSource.text = when {
                        measuredSpeed != null && measured.durationMs < totalMs ->
                            "GPS 實測 ${formatTravelDuration(measured.durationMs)}，里程按全天行駛時間推估"
                        measuredSpeed != null -> "GPS 實測"
                        travel.totalMs > 0 -> "依預設時速估算"
                        else -> ""
                    }
                    travelSource.visibility = if (travelSource.text.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun onStepUpdate() {
        if (!summaryActive || stepRefreshQueued) return
        stepRefreshQueued = true
        summaryHandler.postDelayed(stepRefresh, 600)
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
        disconnect.visibility = if (sync.hasDevice) View.VISIBLE else View.GONE
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
        controlsDialog.dismiss()
        summaryWorker.shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

