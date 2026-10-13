package com.yunpan.tingshu.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.yunpan.tingshu.R

/**
 * 纯 Canvas 柱状图（不引图表库）。每日时长：墨绿柱，今日高亮深色，底部日期标签。
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    data class Bar(val label: String, val valueHours: Float)

    var bars: List<Bar> = emptyList()
        set(value) { field = value; invalidate() }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.primary)
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.onSurface)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.onSurfaceVariant)
        textSize = 26f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val maxVal = bars.maxOf { it.valueHours }.coerceAtLeast(0.01f)
        val chartH = height - 44f
        val slot = width.toFloat() / bars.size
        val barW = slot * 0.6f
        bars.forEachIndexed { i, bar ->
            val h = (bar.valueHours / maxVal * chartH).coerceAtLeast(0f)
            val x = i * slot + (slot - barW) / 2f
            val y = chartH - h
            val paint = if (i == bars.size - 1) todayPaint else barPaint
            canvas.drawRoundRect(RectF(x, y, x + barW, chartH), 8f, 8f, paint)
            // 标签（密度自适应）
            val step = if (bars.size > 15) 5 else 1
            if (i % step == 0) {
                canvas.drawText(bar.label, x, (height - 8f), textPaint)
            }
        }
    }
}
