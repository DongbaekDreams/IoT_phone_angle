package edu.iot.phoneangle.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import edu.iot.phoneangle.R

/**
 * Square heat-map grid. Rows = true class, columns = predicted class.
 * Labels are 1..n; the host screen shows a legend mapping numbers to pose names.
 */
class ConfusionMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var matrix: Array<IntArray> = emptyArray()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.muted)
    }
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val warn = ContextCompat.getColor(context, R.color.status_warn)
    private val empty = ContextCompat.getColor(context, R.color.bg)
    private val ink = ContextCompat.getColor(context, R.color.ink)

    fun setMatrix(m: Array<IntArray>) {
        matrix = m
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (matrix.isEmpty()) 0 else w)
    }

    override fun onDraw(canvas: Canvas) {
        val n = matrix.size
        if (n == 0) return
        val cells = n + 1 // +1 for header row/column
        val size = width.toFloat() / cells
        val gap = size * 0.06f
        textPaint.textSize = size * 0.34f
        labelPaint.textSize = size * 0.30f

        for (i in 0 until n) {
            val cx = (i + 1) * size + size / 2f
            canvas.drawText((i + 1).toString(), cx, size / 2f + labelPaint.textSize / 3f, labelPaint)
            val cy = (i + 1) * size + size / 2f
            canvas.drawText((i + 1).toString(), size / 2f, cy + labelPaint.textSize / 3f, labelPaint)
        }

        for (r in 0 until n) {
            val rowTotal = matrix[r].sum().coerceAtLeast(1)
            for (c in 0 until n) {
                val count = matrix[r][c]
                val frac = count.toFloat() / rowTotal
                val base = if (r == c) accent else warn
                cellPaint.color = if (count == 0) empty else withAlpha(base, 0.15f + 0.85f * frac)
                val rect = RectF(
                    (c + 1) * size + gap,
                    (r + 1) * size + gap,
                    (c + 2) * size - gap,
                    (r + 2) * size - gap,
                )
                canvas.drawRoundRect(rect, size * 0.12f, size * 0.12f, cellPaint)
                if (count > 0) {
                    textPaint.color = if (frac > 0.55f) 0xFFFFFFFF.toInt() else ink
                    canvas.drawText(
                        count.toString(),
                        rect.centerX(),
                        rect.centerY() + textPaint.textSize / 3f,
                        textPaint,
                    )
                }
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
