package com.caw.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Caw orb, as in the Mac app: blue radial gradient, cyan rim, blue glow.
 * A still circle when idle; a wobbling shape driven by [level] while [listening].
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var listening = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Shows the orange "holding text" ring. */
    var holdingText = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Voice level 0..1 (min(1, rms * 3)). */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val dp = resources.displayMetrics.density
    private val path = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BODY
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TINT
        strokeWidth = 2.5f * dp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val d = side * ORB_FRACTION
        val b = d / 2f
        val glow = 0.28f * d
        val t = if (listening) SystemClock.uptimeMillis() / 1000.0 * 2.1 else 0.0

        buildShape(cx, cy, b, t)

        glowPaint.setShadowLayer(glow, 0f, 0f, argb(if (listening) 0.85f else 0.6f, 59, 130, 246))
        canvas.drawPath(path, glowPaint)

        val shift = (if (listening) 0.231f else 0.115f) * b
        val ix = cx + shift * cos(0.8 * t).toFloat()
        val iy = cy + shift * sin(0.8 * t).toFloat()
        val colors = if (listening) LISTENING_COLORS else IDLE_COLORS
        val stops = if (listening) LISTENING_STOPS else IDLE_STOPS
        fillPaint.shader = RadialGradient(ix, iy, 1.154f * b, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint)

        rimPaint.strokeWidth = max(1f * dp, 0.022f * b)
        rimPaint.color = argb(if (listening) 0.95f else 0.75f, 103, 236, 232)
        rimPaint.setShadowLayer(0.5f * glow, 0f, 0f, argb(0.9f, 56, 189, 248))
        canvas.drawPath(path, rimPaint)

        if (holdingText) {
            canvas.drawCircle(cx, cy, b - 2f * dp - ringPaint.strokeWidth / 2f, ringPaint)
        }

        if (listening) postInvalidateOnAnimation()
    }

    private fun buildShape(cx: Float, cy: Float, b: Float, t: Double) {
        path.reset()
        for (i in 0 until POINTS) {
            val a = 2.0 * PI * i / POINTS
            val offset = if (listening) {
                sin(4 * a + 2 * t) * b * 0.077 +
                    cos(7 * a - 3 * t) * b * 0.062 +
                    sin(5 * a + 6 * t) * level * b * 0.215
            } else {
                0.0
            }
            val r = b + offset
            val x = (cx + r * cos(a)).toFloat()
            val y = (cy + r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun argb(alpha: Float, r: Int, g: Int, b: Int) = Color.argb((alpha * 255).toInt(), r, g, b)

    companion object {
        /** Orb diameter as a share of the view, leaving room for the glow and the wobble. */
        private const val ORB_FRACTION = 0.52f
        private const val POINTS = 120
        private val BODY = 0xFF3B82F6.toInt()
        private val TINT = 0xFFE9694F.toInt()

        private val IDLE_COLORS = intArrayOf(
            0xFFE6F4FF.toInt(), 0xFF60A5FA.toInt(), 0xFF3B82F6.toInt(), 0xFF2563EB.toInt(),
        )
        private val LISTENING_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt(), 0xFF4F7CF7.toInt(), 0xFF4F46E5.toInt(),
        )

        // The Mac gradient starts at radius 0.077b and ends at 1.154b. Android's RadialGradient
        // (before API 31) starts at 0, so the stops are moved out to match.
        private fun stops(vararg s: Float): FloatArray {
            val start = 0.077f / 1.154f
            return FloatArray(s.size) { start + s[it] * (1f - start) }
        }

        private val IDLE_STOPS = stops(0f, 0.32f, 0.72f, 1f)
        private val LISTENING_STOPS = stops(0f, 0.22f, 0.52f, 0.82f, 1f)
    }
}
