package edu.iot.phoneangle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val samples = FloatArray(SAMPLE_COUNT)
    private var count = 0
    private var index = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_soft)
        style = Paint.Style.FILL
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private val fillPath = Path()

    fun push(value: Float) {
        samples[index] = value
        index = (index + 1) % SAMPLE_COUNT
        if (count < SAMPLE_COUNT) count++
        invalidate()
    }

    fun clear() {
        count = 0
        index = 0
        samples.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 4f
        val midY = h / 2f
        canvas.drawLine(pad, midY, w - pad, midY, zeroPaint)

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = sampleAt(i)
            if (v < min) min = v
            if (v > max) max = v
        }
        val span = (max - min).coerceAtLeast(8f)
        val center = (max + min) / 2f
        val stepX = (w - pad * 2f) / (count - 1).coerceAtLeast(1)

        path.reset()
        fillPath.reset()
        for (i in 0 until count) {
            val v = sampleAt(i)
            val x = pad + i * stepX
            val y = midY - ((v - center) / span) * (h / 2f - pad)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, midY)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(pad + (count - 1) * stepX, midY)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun sampleAt(i: Int): Float {
        val start = if (count < SAMPLE_COUNT) 0 else index
        return samples[(start + i) % SAMPLE_COUNT]
    }

    companion object {
        private const val SAMPLE_COUNT = 48
    }
}
