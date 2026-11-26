package com.tomforge.tomlink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object LinkColors {
    val page = 0xFFF3F6F2.toInt()
    val surface = 0xFFFFFFFF.toInt()
    val ink = 0xFF192F27.toInt()
    val muted = 0xFF4E665C.toInt()
    val line = 0xFFD4E2D9.toInt()
    val accent = 0xFF205B44.toInt()
    val onAccent = 0xFFF2FFF6.toInt()
    val mint = 0xFFDCEFE3.toInt()
    val mutedMint = 0xFFBCE1CC.toInt()
    val soft = 0xFFE9EFEB.toInt()
    val rose = 0xFFF9E9E4.toInt()
    val roseInk = 0xFF7D3C36.toInt()
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

internal fun Context.panel(fill: Int, radius: Int = 20, border: Int? = null) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    border?.let { setStroke(dp(1), it) }
}

internal fun Context.pressable(fill: Int, radius: Int = 14, border: Int? = null) = RippleDrawable(
    ColorStateList.valueOf(0x2853806A.toInt()), panel(fill, radius, border), panel(LinkColors.surface, radius),
)

internal fun Context.label(value: String, size: Int, bold: Boolean = false, ink: Int = LinkColors.ink) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(ink)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

internal fun Context.action(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
    text = value
    textSize = 14f
    isAllCaps = false
    minHeight = dp(48)
    minimumHeight = dp(48)
    minWidth = 0
    minimumWidth = 0
    elevation = 0f
    stateListAnimator = null
    background = if (primary) pressable(LinkColors.accent) else pressable(LinkColors.soft)
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(if (primary) LinkColors.mutedMint else LinkColors.muted, if (primary) LinkColors.onAccent else LinkColors.ink),
    ))
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setOnClickListener { click() }
}

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal fun Context.card(fill: Int = LinkColors.surface) = column().apply {
    background = panel(fill, border = LinkColors.line.takeIf { fill == LinkColors.surface })
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

internal fun LinearLayout.block(view: View, gap: Int = 0) {
    addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(gap) })
}

internal fun Context.pageScroll(content: LinearLayout) = ScrollView(this).apply {
    isFillViewport = true
    clipToPadding = false
    setBackgroundColor(LinkColors.page)
    content.setPadding(dp(16), dp(12), dp(16), dp(24))
    setOnApplyWindowInsetsListener { _, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(24) + bars.bottom)
        }
        insets
    }
    addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}
