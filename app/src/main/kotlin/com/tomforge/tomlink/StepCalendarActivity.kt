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
        page.addView(label(if (weekly) "活動週曆" else "活動月曆", 26, true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(10))
        })

        if (!weekly) {
            goalLabel = label("", 13)
            page.addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(goalLabel, weighted())
                addView(Button(this@StepCalendarActivity).apply {
                    text = "設定步數目標"
                    setOnClickListener { editGoal() }
                })
            })
            showGoal()
        }

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
            if (!weekly) addView(monthButton("‹", "上個月") { changeMonth(-1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(monthLabel, weighted())
            if (!weekly) addView(nextMonth, LinearLayout.LayoutParams(dp(48), dp(48)))
        })

        calendar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(calendar)
        status = label("", 13).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        page.addView(status)
        page.addView(label(if (weekly) "點日期查看明細。公里數依交通移動時間與設定時速估算。" else "綠色已達標，紅色未達標。點日期查看活動明細。", 13).apply {
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
        val currentSpeeds = speeds
        calendar.addView(row().apply {
            for (title in listOf("一", "二", "三", "四", "五", "六", "日")) {
                addView(label(title, 13, true).apply { gravity = Gravity.CENTER }, weighted(dp(30)))
            }
        })
        for (week in snapshot.days.chunked(7)) {
            calendar.addView(row().apply {
                for (day in week) {
                    val count = snapshot.totals.getValue(day)
                    val travel = snapshot.transport[day] ?: TravelDuration()
                    val km = currentSpeeds.kilometers(travel)
                    val future = day > today
                    val inMonth = YearMonth.from(day) == selected
                    val fill = when {
                        weekly && day == today -> GREEN_TINT
                        weekly -> PAPER
                        !inMonth || future -> MUTED_PAPER
                        count >= goal -> GREEN_TINT
                        else -> RED_TINT
                    }
                    val ink = if (!weekly && (!inMonth || future)) MUTED_INK else INK
                    val state = if (weekly || !inMonth || future) "" else if (count >= goal) "，已達標" else "，未達標"
                    val steps = if (weekly && !future) number.format(count) else if (weekly) "—" else null
                    addView(cell(day.dayOfMonth.toString(), steps, fill, ink).apply {
                        if (weekly) addView(compactValue(if (future) "—" else formatDistance(km, distance), ink))
                        contentDescription = "${day.monthValue} 月 ${day.dayOfMonth} 日$state" +
                            if (weekly && !future) "，${number.format(count)} 步，${formatDistance(km, distance)}" else ""
                        isSelected = day == today
                        if (!future) setOnClickListener { showDayDetails(day, count, travel) }
                    }, cellParams())
                }
            })
        }
        if (weekly) {
            val steps = snapshot.totals.values.sum()
            val km = snapshot.transport.values.sumOf { currentSpeeds.kilometers(it) }
            calendar.addView(label("本週總和　${number.format(steps)} 步 · ${formatDistance(km, distance)}", 17, true).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = tile(GREEN_TINT)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        val summary = "步數　${number.format(steps)} 步\n行車總距離　${formatDistance(current.kilometers(travel), distance)}"
        AlertDialog.Builder(this).setTitle(day.toString())
            .setMessage(if (details.isEmpty()) summary else "$summary\n\n$details")
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

    private fun cell(top: String, bottom: String?, fill: Int, ink: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(1), dp(2), dp(1), dp(2))
            background = tile(fill)
            addView(label(top, 12, true).apply {
                gravity = Gravity.CENTER
                setTextColor(ink)
            }, LinearLayout.LayoutParams(-1, dp(20)))
            if (bottom != null) addView(compactValue(bottom, ink))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

    private fun compactValue(text: String, ink: Int) = label(text, 12, true).apply {
        gravity = Gravity.CENTER
        setTextColor(ink)
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(-1, dp(22))
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weighted(height: Int = -2, weight: Float = 1f) = LinearLayout.LayoutParams(0, height, weight)
    private fun cellParams() = weighted(dp(if (weekly) 72 else 47)).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }

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

    companion object {
        internal const val EXTRA_WEEK = "show_week"
        val INK = Color.rgb(36, 54, 43)
        val MUTED_INK = Color.rgb(97, 110, 102)
        val PAPER = Color.rgb(245, 246, 242)
        val MUTED_PAPER = Color.rgb(239, 240, 237)
        val RED_TINT = Color.rgb(250, 223, 218)
        val GREEN_TINT = Color.rgb(222, 236, 225)
    }
}
