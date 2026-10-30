package com.mamaddoom.ridetalk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mamaddoom.ridetalk.R
import com.mamaddoom.ridetalk.colorOf

/** Rounded level meter that eases towards its value (no jumpy progress bar). */
class LevelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.colorOf(R.attr.cSurface2) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.colorOf(R.color.ok) }
    private val rect = RectF()
    private var target = 0f
    private var shown = 0f

    /** 0..1 */
    fun setLevel(v: Float, hot: Boolean = false) {
        target = v.coerceIn(0f, 1f)
        fg.color = context.colorOf(if (hot) R.attr.cAccent else R.color.ok)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, bg)
        shown += (target - shown) * 0.3f
        val w = width * shown
        if (w > 1f) {
            // Grow from the start edge in both LTR and RTL.
            if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.set(width - w, 0f, width.toFloat(), h)
            else rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, fg)
        }
        if (kotlin.math.abs(target - shown) > 0.003f) postInvalidateOnAnimation()
    }
}
