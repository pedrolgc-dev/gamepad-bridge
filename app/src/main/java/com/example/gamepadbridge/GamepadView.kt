package com.example.gamepadbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws controller controls (joysticks and buttons) on a minimalist black canvas.
 *
 * Drawn in a 1000 x 600 virtual coordinate space that scales to fit
 * the View bounds.
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** State to render. MainActivity calls invalidate() whenever it changes. */
    var gamepad: GamepadState? = null
        set(value) {
            field = value
            invalidate()
        }

    // Pre-allocated Paint instances for FILL, STROKE, and TEXT drawing to avoid allocation churn and style switching
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val box = RectF() // reused to avoid allocations inside onDraw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = gamepad ?: return

        // Scale to fit View while preserving aspect ratio, centered.
        val scale = min(width / VIRTUAL_W, height / VIRTUAL_H)
        canvas.save()
        canvas.translate((width - VIRTUAL_W * scale) / 2f, (height - VIRTUAL_H * scale) / 2f)
        canvas.scale(scale, scale)

        // Analog trigger meters on each side
        val ltVal = maxOf(s.lt, if (s.isPressed(Btn.L2)) 1f else 0f)
        val rtVal = maxOf(s.rt, if (s.isPressed(Btn.R2)) 1f else 0f)
        drawTriggerMeter(canvas, 30f, 76f, 78f, 476f, "LT", ltVal)
        drawTriggerMeter(canvas, 922f, 76f, 970f, 476f, "RT", rtVal)

        // Triggers (analog) and bumpers
        drawTrigger(canvas, 130f, 10f, "LT", ltVal)
        drawTrigger(canvas, 710f, 10f, "RT", rtVal)
        drawBumper(canvas, 110f, 100f, "LB", s.isPressed(Btn.L1))
        drawBumper(canvas, 690f, 100f, "RB", s.isPressed(Btn.R1))

        // Center buttons
        drawModeButton(canvas, 500f, 210f, s.isPressed(Btn.MODE))
        drawSelectButton(canvas, 445f, 285f, s.isPressed(Btn.SELECT))
        drawStartButton(canvas, 555f, 285f, s.isPressed(Btn.START))

        // Sticks
        drawStick(canvas, 255f, 265f, s.lx, s.ly, s.isPressed(Btn.THUMBL))
        drawStick(canvas, 615f, 395f, s.rx, s.ry, s.isPressed(Btn.THUMBR))

        // D-pad
        drawDpad(canvas, 385f, 395f, s.hat())

        // A B X Y face buttons
        drawFaceButton(canvas, 755f, 203f, "Y", YELLOW, s.isPressed(Btn.Y))
        drawFaceButton(canvas, 755f, 327f, "A", GREEN, s.isPressed(Btn.A))
        drawFaceButton(canvas, 693f, 265f, "X", BLUE, s.isPressed(Btn.X))
        drawFaceButton(canvas, 817f, 265f, "B", RED, s.isPressed(Btn.B))

        canvas.restore()
    }

    // ---------------------------------------------------------------
    // Controller elements
    // ---------------------------------------------------------------

    private fun drawTriggerMeter(
        c: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        value: Float
    ) {
        val cx = (left + right) / 2f
        val v = value.coerceIn(0f, 1f)
        val isPressed = v > 0.01f

        // Label above meter
        label(c, text, cx, top - 28f, 26f, GREEN)

        // Well / Track background
        fillPaint.color = WELL
        c.drawRoundRect(rect(left, top, right, bottom), 16f, 16f, fillPaint)

        val trackH = bottom - top
        val maxFillHeight = trackH - 8f
        val fillHeight = maxFillHeight * v
        val fillTop = (bottom - 4f) - fillHeight

        // Fill level
        if (isPressed) {
            val r = minOf(12f, fillHeight / 2f)
            fillPaint.color = GREEN
            c.drawRoundRect(rect(left + 4f, fillTop, right - 4f, bottom - 4f), r, r, fillPaint)

            // Bright indicator line at current level
            strokePaint.color = LABEL
            strokePaint.strokeWidth = 3f
            c.drawLine(left + 4f, fillTop, right - 4f, fillTop, strokePaint)
        }

        // Graduation tick marks
        val innerTop = top + 8f
        val innerBottom = bottom - 8f
        val innerH = innerBottom - innerTop
        for (i in 0..10) {
            val ty = innerBottom - innerH * (i / 10f)
            val isMajor = i == 0 || i == 5 || i == 10
            val isMedium = i % 2 == 0
            val isOverFill = isPressed && ty >= fillTop

            strokePaint.color = if (isOverFill) DARK else KNOB
            strokePaint.strokeWidth = if (isMajor) 2.5f else 1.5f

            if (isMajor) {
                c.drawLine(left + 6f, ty, right - 6f, ty, strokePaint)
            } else if (isMedium) {
                c.drawLine(left + 6f, ty, left + 16f, ty, strokePaint)
                c.drawLine(right - 16f, ty, right - 6f, ty, strokePaint)
            } else {
                c.drawLine(left + 6f, ty, left + 11f, ty, strokePaint)
                c.drawLine(right - 11f, ty, right - 6f, ty, strokePaint)
            }
        }

        // Track border
        strokePaint.color = STROKE
        strokePaint.strokeWidth = 3f
        c.drawRoundRect(rect(left, top, right, bottom), 16f, 16f, strokePaint)

        // Percentage readout below meter (cached string array avoids allocations)
        val pct = (v * 100f).roundToInt().coerceIn(0, 100)
        if (pct > 0) {
            label(c, PERCENT_STRINGS[pct], cx, bottom + 32f, 22f, GREEN)
        }
    }

    private fun drawTrigger(c: Canvas, left: Float, top: Float, text: String, value: Float) {
        val right = left + 160f
        val bottom = top + 80f
        fillPaint.color = SURFACE
        c.drawRoundRect(rect(left, top, right, bottom), 24f, 24f, fillPaint)

        val v = value.coerceIn(0f, 1f)
        if (v > 0.01f) {
            val fillTop = bottom - 80f * v
            fillPaint.color = GREEN
            c.drawRoundRect(rect(left, fillTop, right, bottom), 16f, 16f, fillPaint)
        }
        label(c, text, left + 80f, top + 40f, 30f, LABEL)
    }

    private fun drawBumper(c: Canvas, left: Float, top: Float, text: String, pressed: Boolean) {
        fillPaint.color = if (pressed) GREEN else SURFACE
        c.drawRoundRect(rect(left, top, left + 200f, top + 42f), 18f, 18f, fillPaint)
        label(c, text, left + 100f, top + 21f, 26f, LABEL)
    }

    private fun drawStick(c: Canvas, cx: Float, cy: Float, x: Float, y: Float, pressed: Boolean) {
        // Base
        fillPaint.color = WELL
        c.drawCircle(cx, cy, 78f, fillPaint)
        strokePaint.color = STROKE
        strokePaint.strokeWidth = 4f
        c.drawCircle(cx, cy, 78f, strokePaint)

        // Knob moves according to stick offset (constrained to a circle).
        val len = sqrt(x * x + y * y)
        val k = if (len > 1f) 1f / len else 1f
        val kx = cx + x * k * 32f
        val ky = cy + y * k * 32f
        fillPaint.color = if (pressed) GREEN else KNOB
        c.drawCircle(kx, ky, 44f, fillPaint)
        strokePaint.color = if (pressed) LABEL else STROKE
        strokePaint.strokeWidth = 3f
        c.drawCircle(kx, ky, 44f, strokePaint)
    }

    private fun drawDpad(c: Canvas, cx: Float, cy: Float, hat: Int) {
        val up = hat == 7 || hat == 0 || hat == 1
        val right = hat in 1..3
        val down = hat in 3..5
        val left = hat in 5..7

        drawDpadSegment(c, cx - 19f, cy - 55f, cx + 19f, cy - 19f, up)
        drawDpadSegment(c, cx - 19f, cy + 19f, cx + 19f, cy + 55f, down)
        drawDpadSegment(c, cx - 55f, cy - 19f, cx - 19f, cy + 19f, left)
        drawDpadSegment(c, cx + 19f, cy - 19f, cx + 55f, cy + 19f, right)
        drawDpadSegment(c, cx - 19f, cy - 19f, cx + 19f, cy + 19f, false) // center
    }

    private fun drawDpadSegment(c: Canvas, l: Float, t: Float, r: Float, b: Float, on: Boolean) {
        fillPaint.color = if (on) GREEN else SURFACE
        c.drawRoundRect(rect(l, t, r, b), 6f, 6f, fillPaint)
    }

    private fun drawFaceButton(
        c: Canvas, cx: Float, cy: Float, text: String, color: Int, pressed: Boolean
    ) {
        if (pressed) {
            fillPaint.color = color
            c.drawCircle(cx, cy, 31f, fillPaint)
            label(c, text, cx, cy, 34f, DARK)
        } else {
            fillPaint.color = SURFACE
            c.drawCircle(cx, cy, 31f, fillPaint)
            strokePaint.color = color
            strokePaint.strokeWidth = 4f
            c.drawCircle(cx, cy, 31f, strokePaint)
            label(c, text, cx, cy, 34f, color)
        }
    }

    private fun drawModeButton(c: Canvas, cx: Float, cy: Float, pressed: Boolean) {
        fillPaint.color = if (pressed) GREEN else SURFACE
        c.drawCircle(cx, cy, 26f, fillPaint)
        strokePaint.color = if (pressed) LABEL else STROKE
        strokePaint.strokeWidth = 3f
        c.drawCircle(cx, cy, 26f, strokePaint)
        fillPaint.color = if (pressed) DARK else LABEL
        c.drawCircle(cx, cy, 8f, fillPaint)
    }

    private fun drawSelectButton(c: Canvas, cx: Float, cy: Float, pressed: Boolean) {
        fillPaint.color = if (pressed) GREEN else SURFACE
        c.drawCircle(cx, cy, 20f, fillPaint)
        // Icon: two overlapping squares
        strokePaint.color = if (pressed) DARK else LABEL
        strokePaint.strokeWidth = 3f
        c.drawRect(cx - 10f, cy - 8f, cx + 2f, cy + 3f, strokePaint)
        c.drawRect(cx - 3f, cy - 2f, cx + 9f, cy + 9f, strokePaint)
    }

    private fun drawStartButton(c: Canvas, cx: Float, cy: Float, pressed: Boolean) {
        fillPaint.color = if (pressed) GREEN else SURFACE
        c.drawCircle(cx, cy, 20f, fillPaint)
        // Icon: three lines (menu)
        strokePaint.color = if (pressed) DARK else LABEL
        strokePaint.strokeWidth = 3f
        strokePaint.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 9f, cy - 7f, cx + 9f, cy - 7f, strokePaint)
        c.drawLine(cx - 9f, cy, cx + 9f, cy, strokePaint)
        c.drawLine(cx - 9f, cy + 7f, cx + 9f, cy + 7f, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    // ---------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------

    private fun rect(l: Float, t: Float, r: Float, b: Float): RectF {
        box.set(l, t, r, b)
        return box
    }

    /** Draws centered text at (cx, cy). */
    private fun label(c: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = size
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(text, cx, y, textPaint)
    }

    private companion object {
        const val VIRTUAL_W = 1000f
        const val VIRTUAL_H = 600f

        val PERCENT_STRINGS = Array(101) { "$it%" }

        val SURFACE = 0xFF1C2128.toInt()
        val WELL = 0xFF101216.toInt()
        val KNOB = 0xFF38404E.toInt()
        val STROKE = 0xFF2A303C.toInt()
        val LABEL = 0xFFE6EAF2.toInt()
        val DARK = 0xFF000000.toInt()
        val ACCENT = 0xFF4F8CFF.toInt()
        val GREEN = 0xFF3DBE6B.toInt()
        val RED = 0xFFE5484D.toInt()
        val BLUE = 0xFF4F8CFF.toInt()
        val YELLOW = 0xFFF5C542.toInt()
    }
}
