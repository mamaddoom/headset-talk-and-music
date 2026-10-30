package com.mamaddoom.ridetalk.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.mamaddoom.ridetalk.R
import com.mamaddoom.ridetalk.colorOf
import kotlin.math.min

/**
 * The big round talk button: colour follows the talk state with a smooth
 * cross-fade, pulse rings radiate while the mic is live, and an arc around
 * the edge shows the mic level.
 */
class TalkButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { OFF, IDLE, PREPARING, LIVE, MUTED }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()

    private var mode = Mode.OFF
    private var colour = context.colorOf(R.attr.cSurface2)
    private var colourAnim: ValueAnimator? = null
    private var pulse = 0f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }
    private var levelTarget = 0f
    private var levelShown = 0f

    private val density = resources.displayMetrics.density

    init {
        track.strokeWidth = 6 * density
        track.color = context.colorOf(R.attr.cStroke)
        level.strokeWidth = 6 * density
        level.color = context.colorOf(R.color.ok)
        ring.strokeWidth = 2 * density
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        val to = context.colorOf(
            when (m) {
                Mode.OFF -> R.attr.cSurface2
                Mode.IDLE -> R.attr.cAccentDark
                Mode.PREPARING -> R.attr.cAccentMid
                Mode.LIVE -> R.attr.cAccent
                Mode.MUTED -> R.color.danger_dark
            },
        )
        colourAnim?.cancel()
        colourAnim = ValueAnimator.ofObject(ArgbEvaluator(), colour, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                colour = it.animatedValue as Int
                invalidate()
            }
            start()
        }
        val pulsing = m == Mode.LIVE || m == Mode.PREPARING
        if (pulsing && !pulseAnim.isStarted) pulseAnim.start()
        if (!pulsing) {
            pulseAnim.cancel()
            pulse = 0f
        }
    }

    /** Mic level 0..1; the arc eases towards it. */
    fun setLevel(v: Float) {
        levelTarget = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        pulseAnim.cancel()
        colourAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val r = maxR * 0.72f

        // Pulse rings (two, half a cycle apart).
        if (pulse > 0f || mode == Mode.LIVE || mode == Mode.PREPARING) {
            for (k in 0..1) {
                val p = (pulse + k * 0.5f) % 1f
                ring.color = context.colorOf(R.attr.cAccent)
                ring.alpha = ((1f - p) * (if (mode == Mode.LIVE) 160 else 90)).toInt()
                canvas.drawCircle(cx, cy, r + (maxR - r) * p, ring)
            }
        }

        // Level arc around the button.
        levelShown += (levelTarget - levelShown) * 0.35f
        val ar = r + 10 * density
        arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
        canvas.drawArc(arc, 135f, 270f, false, track)
        if (levelShown > 0.01f) canvas.drawArc(arc, 135f, 270f * levelShown, false, level)

        fill.color = colour
        canvas.drawCircle(cx, cy, r, fill)

        if (kotlin.math.abs(levelTarget - levelShown) > 0.005f) postInvalidateOnAnimation()
    }
}
