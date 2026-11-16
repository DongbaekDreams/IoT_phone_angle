package edu.iot.phoneangle.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import edu.iot.phoneangle.R
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.ml.typed.KeyboardLayout

/**
 * Minimal QWERTY keyboard for **training collection only**. It draws [KeyboardLayout] exactly,
 * so every touch gives a ground-truth tap position (normalized to the keyboard) and a
 * touch-down time on the sensor clock. Multi-touch aware for two-thumb typing.
 *
 * Live flagging never uses this view or any touch data.
 */
class TapKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called on the UI thread for every key press. */
    var onKey: ((TouchLabel) -> Unit)? = null

    private data class DrawKey(val label: String, val text: String, val left: Float, val right: Float, val row: Int)

    private val drawKeys: List<DrawKey> = buildList {
        for (k in KeyboardLayout.keys) {
            val row = (k.cy / KeyboardLayout.KEY_H).toInt()
            val text = when (k.label) {
                TouchLabel.KEY_SPACE -> "space"
                TouchLabel.KEY_ENTER -> "search"
                TouchLabel.KEY_BACKSPACE -> "⌫"
                else -> k.label
            }
            add(DrawKey(k.label, text, k.cx - k.width / 2, k.cx + k.width / 2, row))
        }
        // Inert keys that real keyboards have in these spots (touches still recorded).
        add(DrawKey(SHIFT, "⇧", 0f, 0.15f, 2))
        add(DrawKey(SYMBOLS, "?123", 0f, 0.15f, 3))
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_high)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sky)
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_soft)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val pressed = HashMap<Int, DrawKey>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (ROW_HEIGHT_DP * KeyboardLayout.ROWS * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec))
    }

    private val kbWidth: Float get() = (width - paddingLeft - paddingRight).toFloat()
    private val kbHeight: Float get() = (height - paddingTop - paddingBottom).toFloat()

    override fun onDraw(canvas: Canvas) {
        val rowH = kbHeight / KeyboardLayout.ROWS
        val gap = 3f * resources.displayMetrics.density
        textPaint.textSize = rowH * 0.36f
        for (k in drawKeys) {
            rect.set(
                paddingLeft + k.left * kbWidth + gap,
                paddingTop + k.row * rowH + gap,
                paddingLeft + k.right * kbWidth - gap,
                paddingTop + (k.row + 1) * rowH - gap,
            )
            val paint = when {
                pressed.containsValue(k) -> pressedPaint
                k.label.length > 1 -> actionPaint
                else -> keyPaint
            }
            canvas.drawRoundRect(rect, gap * 2, gap * 2, paint)
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(k.text, rect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val nx = ((event.getX(idx) - paddingLeft) / kbWidth).coerceIn(0f, 1f)
                val ny = ((event.getY(idx) - paddingTop) / kbHeight).coerceIn(0f, 1f)
                val key = hit(nx, ny)
                pressed[event.getPointerId(idx)] = key
                onKey?.invoke(TouchLabel(sensorClockNs(event), nx, ny, key.label))
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pressed.remove(event.getPointerId(event.actionIndex))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                invalidate()
            }
        }
        return true
    }

    private fun hit(nx: Float, ny: Float): DrawKey {
        val row = (ny * KeyboardLayout.ROWS).toInt().coerceIn(0, KeyboardLayout.ROWS - 1)
        val inRow = drawKeys.filter { it.row == row }
        return inRow.firstOrNull { nx >= it.left && nx < it.right }
            ?: inRow.minBy { minOf(kotlin.math.abs(nx - it.left), kotlin.math.abs(nx - it.right)) }
    }

    /**
     * MotionEvent times are on the uptime clock; SensorEvent timestamps are elapsedRealtimeNanos.
     * The difference is constant while the device is awake, so shift by the current offset.
     */
    private fun sensorClockNs(event: MotionEvent): Long {
        val eventUptimeNs = if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * 1_000_000L
        val offsetNs = SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L
        return eventUptimeNs + offsetNs
    }

    companion object {
        const val SHIFT = "shift"
        const val SYMBOLS = "symbols"
        private const val ROW_HEIGHT_DP = 54f
    }
}
