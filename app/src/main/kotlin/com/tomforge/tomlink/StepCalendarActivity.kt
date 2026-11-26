package com.tomforge.tomlink

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.core.view.ViewCompat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

class StepCalendarActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val number = NumberFormat.getIntegerInstance()
    private val distance = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val transportSettings by lazy { getSharedPreferences("transport", MODE_PRIVATE) }
    private val speeds get() = travelSpeeds(this)
    private val weekly get() = intent.getBooleanExtra(EXTRA_WEEK, false)
    private val goals by lazy { getSharedPreferences("activity_goals", MODE_PRIVATE) }
    private val goal get() = goals.getLong("dailySteps", 8000)
    private lateinit var goalLabel: TextView
    private lateinit var speedLabel: TextView
    private var month = YearMonth.now()
    private var requestId = 0
    private var loading = false
    private lateinit var monthLabel: TextView
    private lateinit var nextMonth: Button
    private lateinit var calendar: LinearLayout
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            if (!loading) load()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("month")?.let { month = YearMonth.parse(it) }
        val page = column()
        val back = action("‹") { finish() }.apply { contentDescription = "返回首頁"; textSize = 24f }
        val title = label(if (weekly) "活動週曆" else "活動月曆", 25, true)
        ViewCompat.setAccessibilityHeading(title, true)
        page.block(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(48), -2))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(action("設定") { openCalendarSettings() })
        })
        monthLabel = label("", 17, true).apply { gravity = Gravity.CENTER }
        nextMonth = monthButton("›", "下個月") { changeMonth(1) }
        page.block(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (!weekly) addView(monthButton("‹", "上個月") { changeMonth(-1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(monthLabel, LinearLayout.LayoutParams(0, -2, 1f))
            if (!weekly) addView(nextMonth, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, 12)
        calendar = column()
        if (weekly) page.block(calendar, 9)
        else page.block(card().apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            block(calendar)
        }, 9)
        status = label("", 14, ink = LinkColors.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.block(status, 6)
        if (!weekly) {
            page.block(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                fun marker(text: String, color: Int, ink: Int) {
                    addView(label(text, 13, ink = ink).apply {
                        background = panel(color, 12)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                }
                marker("已達標", LinkColors.mint, LinkColors.accent)
                marker("未達標", LinkColors.rose, LinkColors.roseInk)
            }, 6)
        }
        setContentView(pageScroll(page))
    }

    private fun openCalendarSettings() {
        val content = column().apply { setPadding(dp(22), dp(4), dp(22), dp(8)) }
        goalLabel = label("", 14, ink = LinkColors.muted)
        content.block(goalLabel)
        showGoal()
        content.block(action("設定步數目標") { editGoal() }, 6)
        speedLabel = label("", 14, ink = LinkColors.muted)
        content.block(speedLabel, 16)
        showTravelSpeeds()
        content.block(action("設定估算時速") { editTravelSpeeds() }, 6)
        AlertDialog.Builder(this).setTitle("活動紀錄設定").setView(content)
            .setPositiveButton("完成", null).show()
    }
    override fun onResume() {
        super.onResume()
        load()
        handler.postDelayed(refresh, 30_000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("month", month.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun changeMonth(offset: Long) {
        month = month.plusMonths(offset)
        load()
    }

    private data class Snapshot(
        val days: List<LocalDate>,
        val totals: Map<LocalDate, Long>,
        val transport: Map<LocalDate, TravelDuration>,
    )

    private fun load() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val currentMonth = YearMonth.from(today)
        if (month > currentMonth) month = currentMonth
        val selected = month
        val id = ++requestId
        val monday = mondayOf(today)
        monthLabel.text = if (weekly) "$monday ～ ${monday.plusDays(6)}" else "${selected.year} 年 ${selected.monthValue} 月"
        nextMonth.isEnabled = selected < currentMonth
        loading = true
        StepService.flushPending?.invoke()
        worker.execute {
            val result = runCatching {
                val dates = if (weekly) List(7) { monday.plusDays(it.toLong()) } else calendarDays(selected)
                val first = dates.first()
                val until = dates.last().plusDays(1)
                StepStore(applicationContext).use { store ->
                    Snapshot(dates, store.stepsByDay(first, until, zone), store.transportByDay(first, until, zone))
                }
            }
            runOnUiThread {
                if (isDestroyed || id != requestId) return@runOnUiThread
                loading = false
                result.onSuccess { snapshot ->
                    status.text = ""
                    render(selected, today, snapshot)
                }.onFailure {
                    calendar.removeAllViews()
                    status.text = "暫時無法讀取活動，返回後再試一次。"
                }
            }
        }
    }

    private fun render(selected: YearMonth, today: LocalDate, snapshot: Snapshot) {
        calendar.removeAllViews()
        if (weekly) {
            renderWeek(today, snapshot)
            return
        }
        val currentSpeeds = speeds
        calendar.block(LinearLayout(this).apply {
            for (title in WEEKDAYS) {
                addView(label(title, 13, true, LinkColors.muted).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, dp(32), 1f))
            }
        })
        for (week in snapshot.days.chunked(7)) {
            calendar.block(LinearLayout(this).apply {
                for (day in week) {
                    val count = snapshot.totals.getValue(day)
                    val travel = snapshot.transport[day] ?: TravelDuration()
                    val future = day > today
                    val inMonth = YearMonth.from(day) == selected
                    val eligible = inMonth && !future
                    val fill = when {
                        !eligible -> LinkColors.soft
                        count >= goal -> LinkColors.mint
                        else -> LinkColors.rose
                    }
                    val ink = when {
                        !eligible -> LinkColors.muted
                        count >= goal -> LinkColors.accent
                        else -> LinkColors.roseInk
                    }
                    val outline = LinkColors.accent.takeIf { day == today }
                    val tile = label(day.dayOfMonth.toString(), 15, day == today, ink).apply {
                        gravity = Gravity.CENTER
                        background = if (future) panel(fill, 10, outline) else pressable(fill, 10, outline)
                        contentDescription = "${day.monthValue} 月 ${day.dayOfMonth} 日" +
                            (if (day == today) "，今天" else "") +
                            if (future) "，未來日期" else "，${number.format(count)} 步，${formatDistance(currentSpeeds.kilometers(travel), distance)}" +
                                if (!eligible) "" else if (count >= goal) "，已達標" else "，未達標"
                        if (!future) {
                            isFocusable = true
                            setOnClickListener { showDayDetails(day, count, travel) }
                        }
                    }
                    addView(tile, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(1), 0, dp(1), dp(3)) })
                }
            })
        }
    }

    private fun renderWeek(today: LocalDate, snapshot: Snapshot) {
        val currentSpeeds = speeds
        val steps = snapshot.totals.values.sum()
        val km = snapshot.transport.values.sumOf { currentSpeeds.kilometers(it) }
        calendar.block(card(LinkColors.mint).apply {
            block(label("本週總計", 14, true, LinkColors.accent))
            block(label("${number.format(steps)} 步", 28, true), 3)
            block(label("估算距離 ${formatDistance(km, distance)}", 14, ink = LinkColors.muted), 3)
        })
        snapshot.days.forEachIndexed { index, day ->
            val count = snapshot.totals.getValue(day)
            val travel = snapshot.transport[day] ?: TravelDuration()
            val future = day > today
            val date = "${day.monthValue}/${day.dayOfMonth}（${WEEKDAYS[index]}）"
            val stepText = if (future) "—" else "${number.format(count)} 步"
            val distanceText = if (future) "—" else formatDistance(currentSpeeds.kilometers(travel), distance)
            calendar.block(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                minimumHeight = dp(48)
                val fill = if (day == today) LinkColors.mint else LinkColors.surface
                background = if (future) panel(fill, 12) else pressable(fill, 12)
                addView(label(if (day == today) "今天 $date" else date, 14, day == today),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(column().apply {
                    gravity = Gravity.END
                    block(label(stepText, 14, !future))
                    block(label(distanceText, 12, ink = LinkColors.muted))
                })
                if (!future) {
                    isFocusable = true
                    setOnClickListener { showDayDetails(day, count, travel) }
                }
                contentDescription = "$date，$stepText，$distanceText"
            }, 5)
        }
    }
    private fun showGoal() {
        goalLabel.text = "每日目標 ${number.format(goal)} 步"
    }

    private fun editGoal() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(goal.toString())
            selectAll()
        }
        val fields = LinearLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), dp(4))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle("每日步數目標").setView(fields)
            .setNegativeButton("取消", null).setPositiveButton("儲存", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val steps = input.text.toString().toLongOrNull()
            if (steps == null || steps <= 0) {
                input.error = "請輸入大於 0 的步數"
            } else {
                goals.edit().putLong("dailySteps", steps).apply()
                showGoal()
                dialog.dismiss()
                load()
            }
        }
    }

    private fun showTravelSpeeds() {
        val current = speeds
        speedLabel.text = "搭車 ${current.vehicleKph} km/h · 單車 ${current.bicycleKph} km/h"
    }

    private fun showDayDetails(day: LocalDate, steps: Long, travel: TravelDuration) {
        val current = speeds
        val details = listOf(
            Triple("搭車", travel.vehicleMs, current.vehicleKph),
            Triple("騎單車", travel.bicycleMs, current.bicycleKph),
        ).filter { it.second > 0 }.joinToString("\n") { (mode, ms, kph) ->
            "$mode　${formatTravelDuration(ms)} · $kph km/h · ${formatDistance(estimatedKm(ms, kph), distance)}"
        }
        val summary = column().apply {
            setPadding(dp(22), dp(4), dp(22), dp(8))
            block(label("${number.format(steps)} 步", 28, true))
            block(label("估算距離 ${formatDistance(current.kilometers(travel), distance)}", 16, ink = LinkColors.muted), 6)
            if (details.isNotEmpty()) block(label(details, 14, ink = LinkColors.muted), 12)
        }
        AlertDialog.Builder(this).setTitle(day.toString())
            .setView(summary)
            .setPositiveButton("關閉", null).show()
    }

    private fun editTravelSpeeds() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        fun speedField(title: String, value: Int): EditText {
            val caption = label(title, 14, false)
            val input = EditText(this).apply {
                id = View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            caption.labelFor = input.id
            fields.addView(caption)
            fields.addView(input)
            return input
        }
        val current = speeds
        val vehicle = speedField("搭車（公里／小時）", current.vehicleKph)
        val bicycle = speedField("騎單車（公里／小時）", current.bicycleKph)
        val dialog = AlertDialog.Builder(this).setTitle("設定估算時速").setView(fields)
            .setNegativeButton("取消", null).setPositiveButton("儲存", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val vehicleKph = vehicle.text.toString().toIntOrNull()
            val bicycleKph = bicycle.text.toString().toIntOrNull()
            if (vehicleKph == null || vehicleKph !in 1..400) {
                vehicle.error = "請輸入 1 到 400"
            } else if (bicycleKph == null || bicycleKph !in 1..100) {
                bicycle.error = "請輸入 1 到 100"
            } else {
                transportSettings.edit().putInt("vehicleKph", vehicleKph).putInt("bicycleKph", bicycleKph).apply()
                showTravelSpeeds()
                dialog.dismiss()
                load()
            }
        }
    }

    private fun monthButton(symbol: String, description: String, go: () -> Unit) = action(symbol, click = go).apply {
        textSize = 25f
        contentDescription = description
        setPadding(0, 0, 0, 0)
    }

    companion object {
        internal const val EXTRA_WEEK = "show_week"
    }
}