package com.tomforge.tomlink

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors

class StepCalendarActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val number = NumberFormat.getIntegerInstance()
    private val distance = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val transportSettings by lazy { getSharedPreferences("transport", MODE_PRIVATE) }
    private val speeds get() = TravelSpeeds(
        transportSettings.getInt("vehicleKph", 30),
        transportSettings.getInt("bicycleKph", 15),
    )
    private lateinit var speedLabel: TextView
    private var month = YearMonth.now()
    private var requestId = 0
    private var loading = false
    private lateinit var monthLabel: TextView
    private lateinit var todayCount: TextView
    private lateinit var weekCount: TextView
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
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        page.setPadding(dp(12), dp(12), dp(12), dp(20))
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                page.setPadding(dp(12) + bars.left, dp(12) + bars.top, dp(12) + bars.right, dp(20) + bars.bottom)
            }
            insets
        }

        page.addView(Button(this).apply {
            text = "返回 TomLink"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2))
        page.addView(label("活動月曆", 26, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(10))
        })

        todayCount = label("—", 22, true)
        weekCount = label("—", 22, true)
        page.addView(row().apply {
            addView(summary("今日步數", todayCount), weighted())
            addView(summary("本週總步數", weekCount), weighted())
        })

        speedLabel = label("", 13)
        page.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(speedLabel, weighted())
            addView(Button(this@StepCalendarActivity).apply {
                text = "設定估算時速"
                setOnClickListener { editTravelSpeeds() }
            })
        })
        showTravelSpeeds()
        monthLabel = label("", 18, true).apply { gravity = Gravity.CENTER }
        nextMonth = monthButton("›", "下個月") { changeMonth(1) }
        page.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(monthButton("‹", "上個月") { changeMonth(-1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(monthLabel, weighted())
            addView(nextMonth, LinearLayout.LayoutParams(dp(48), dp(48)))
        })

        calendar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(calendar)
        status = label("", 13).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        page.addView(status)
        page.addView(label("距離為移動時間乘以估算時速，未使用 GPS。點日期可看搭車與騎單車的明細。", 13).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        setContentView(scroll)
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
        val today: Long,
        val week: Long,
    )

    private fun load() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val currentMonth = YearMonth.from(today)
        if (month > currentMonth) month = currentMonth
        val selected = month
        val id = ++requestId
        monthLabel.text = "${selected.year} 年 ${selected.monthValue} 月"
        nextMonth.isEnabled = selected < currentMonth
        loading = true
        StepService.flushPending?.invoke()
        worker.execute {
            val result = runCatching {
                val dates = calendarDays(selected)
                val first = dates.first()
                val until = dates.last().plusDays(1)
                val weekStart = mondayOf(today)
                val weekEnd = weekStart.plusWeeks(1)
                StepStore(applicationContext).use { store ->
                    val days = store.stepsByDay(first, until, zone)
                    val currentWeek = if (weekStart >= first && weekEnd <= until) {
                        days.filterKeys { it >= weekStart && it < weekEnd }
                    } else {
                        store.stepsByDay(weekStart, weekEnd, zone)
                    }
                    Snapshot(dates, days, store.transportByDay(first, until, zone), currentWeek[today] ?: 0, currentWeek.values.sum())
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
                    todayCount.text = "—"
                    weekCount.text = "—"
                    status.text = "暫時無法讀取步數，返回後再試一次。"
                }
            }
        }
    }

    private fun render(selected: YearMonth, today: LocalDate, snapshot: Snapshot) {
        todayCount.text = number.format(snapshot.today)
        weekCount.text = number.format(snapshot.week)
        calendar.removeAllViews()
        calendar.addView(row().apply {
            for (title in listOf("一", "二", "三", "四", "五", "六", "日")) {
                addView(label(title, 13, true).apply { gravity = Gravity.CENTER }, weighted(dp(30)))
            }
            addView(label("週合計", 12, true).apply { gravity = Gravity.CENTER }, weighted(dp(30), 1.35f))
        })
        for (week in snapshot.days.chunked(7)) {
            calendar.addView(row().apply {
                for (day in week) {
                    val count = snapshot.totals.getValue(day)
                    addView(dayCell(day, count, snapshot.transport[day] ?: TravelDuration(), YearMonth.from(day) == selected, day == today), cellParams())
                }
                val total = week.sumOf { snapshot.totals.getValue(it) }
                val km = week.sumOf { speeds.kilometers(snapshot.transport[it] ?: TravelDuration()) }
                addView(cell("合計", number.format(total), GREEN_TINT, INK).apply {
                    contentDescription = "${week.first()} 至 ${week.last()}，共 ${number.format(total)} 步"
                    if (km > 0) addView(label(formatDistance(km), 10, false).apply { gravity = Gravity.CENTER })
                }, cellParams(1.35f))
            })
        }
    }

    private fun dayCell(day: LocalDate, count: Long, transport: TravelDuration, inMonth: Boolean, isToday: Boolean) =
        cell(day.dayOfMonth.toString(), if (count > 0 || isToday) number.format(count) else "—",
            if (isToday) GREEN_TINT else if (inMonth) PAPER else MUTED_PAPER,
            if (inMonth || isToday) INK else MUTED_INK).apply {
            contentDescription = "${day.monthValue} 月 ${day.dayOfMonth} 日，${number.format(count)} 步"
            if (transport.totalMs > 0) {
                val duration = formatDuration(transport.totalMs)
                val km = formatDistance(speeds.kilometers(transport))
                addView(label(duration, 10, false).apply { gravity = Gravity.CENTER })
                addView(label(km, 10, false).apply { gravity = Gravity.CENTER })
                contentDescription = "$contentDescription，交通移動 $duration，$km"
                setOnClickListener { showTravelDetails(day, transport) }
            }
            isSelected = isToday
        }

    private fun formatDuration(ms: Long) = if (ms >= 60_000) "${ms / 60_000} 分" else "<1 分"

    private fun formatDistance(km: Double): String =
        if (km in 0.0..<0.05) "<0.1 km" else "約 ${distance.format(km)} km"

    private fun showTravelSpeeds() {
        val current = speeds
        speedLabel.text = "搭車 ${current.vehicleKph} km/h · 單車 ${current.bicycleKph} km/h"
    }

    private fun showTravelDetails(day: LocalDate, travel: TravelDuration) {
        val current = speeds
        val details = listOf(
            Triple("搭車", travel.vehicleMs, current.vehicleKph),
            Triple("騎單車", travel.bicycleMs, current.bicycleKph),
        ).filter { it.second > 0 }.joinToString("\n") { (mode, ms, kph) ->
            "$mode　${formatDuration(ms)} × $kph km/h = ${formatDistance(estimatedKm(ms, kph))}"
        }
        AlertDialog.Builder(this).setTitle("$day 交通移動").setMessage(details)
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

    private fun cell(top: String, bottom: String, fill: Int, ink: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(1), dp(2), dp(1), dp(2))
            background = tile(fill)
            addView(label(top, 12, true).apply {
                gravity = Gravity.CENTER
                setTextColor(ink)
            }, LinearLayout.LayoutParams(-1, dp(20)))
            addView(label(bottom, 13, true).apply {
                gravity = Gravity.CENTER
                setTextColor(ink)
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(-1, dp(25)))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

    private fun summary(title: String, count: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = tile(GREEN_TINT)
        addView(label(title, 13))
        addView(count)
        layoutParams = weighted()
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted(height: Int = -2, weight: Float = 1f) = LinearLayout.LayoutParams(0, height, weight)
    private fun cellParams(weight: Float = 1f) = weighted(dp(53), weight).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }

    private fun monthButton(symbol: String, description: String, action: () -> Unit) = Button(this).apply {
        text = symbol
        textSize = 25f
        contentDescription = description
        minWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(INK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun tile(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(5).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private companion object {
        val INK = Color.rgb(36, 54, 43)
        val MUTED_INK = Color.rgb(97, 110, 102)
        val PAPER = Color.rgb(245, 246, 242)
        val MUTED_PAPER = Color.rgb(239, 240, 237)
        val GREEN_TINT = Color.rgb(222, 236, 225)
    }
}
