package edu.iot.phoneangle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Projects a real phone box in 3D from pitch / roll / yaw.
 * No fake Y-scaling — tilts keep volume instead of squashing flat.
 */
class PhoneOrientationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.phone_body)
        style = Paint.Style.FILL
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.phone_body_side)
        style = Paint.Style.FILL
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.phone_screen)
        style = Paint.Style.FILL
    }
    private val screenBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.phone_screen_dark)
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.faint)
        textAlign = Paint.Align.CENTER
        textSize = 22f
        letterSpacing = 0.14f
    }

    private var pitchDeg = 0f
    private var rollDeg = 0f
    private var yawDeg = 0f

    private val path = Path()

    // Upright phone facing the camera: +X right, +Y top, +Z toward viewer
    private val hw = 0.38f
    private val hh = 0.72f
    private val hd = 0.055f

    private val corners = arrayOf(
        floatArrayOf(-hw, -hh, -hd), // 0 back-bottom-left
        floatArrayOf(hw, -hh, -hd),  // 1
        floatArrayOf(hw, hh, -hd),   // 2
        floatArrayOf(-hw, hh, -hd),  // 3
        floatArrayOf(-hw, -hh, hd),  // 4 front-bottom-left
        floatArrayOf(hw, -hh, hd),   // 5
        floatArrayOf(hw, hh, hd),    // 6
        floatArrayOf(-hw, hh, hd),   // 7
    )

    private val screenLocal = arrayOf(
        floatArrayOf(-hw + 0.08f, -hh + 0.10f, hd + 0.002f),
        floatArrayOf(hw - 0.08f, -hh + 0.10f, hd + 0.002f),
        floatArrayOf(hw - 0.08f, hh - 0.10f, hd + 0.002f),
        floatArrayOf(-hw + 0.08f, hh - 0.10f, hd + 0.002f),
    )

    private val faces = arrayOf(
        intArrayOf(4, 5, 6, 7), // front
        intArrayOf(1, 0, 3, 2), // back
        intArrayOf(5, 1, 2, 6), // right
        intArrayOf(0, 4, 7, 3), // left
        intArrayOf(7, 6, 2, 3), // top
        intArrayOf(0, 1, 5, 4), // bottom
    )

    fun setAngles(pitch: Float, roll: Float, yaw: Float = 0f) {
        pitchDeg = pitch
        rollDeg = roll
        yawDeg = yaw
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h * 0.62f
        val r = min(w, h) * 0.42f
        groundPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0x332C6E68, 0x142C6E68, 0x00EEF1F4),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.48f
        val scale = min(w, h) * 0.40f

        // Soft ground disc — stays level; phone moves above it
        val gcx = w / 2f
        val gcy = h * 0.72f
        val gr = min(w, h) * 0.38f
        canvas.drawCircle(gcx, gcy, gr, groundPaint)
        canvas.drawCircle(gcx, gcy, gr * 0.92f, groundRing)
        canvas.drawText("GROUND", gcx, h - 20f, labelPaint)

        val projected = Array(8) { FloatArray(3) }
        for (i in corners.indices) {
            projected[i] = project(corners[i][0], corners[i][1], corners[i][2])
        }

        data class Face(val z: Float, val indices: IntArray, val id: Int)
        val ordered = faces.mapIndexed { id, idx ->
            Face(idx.map { projected[it][2] }.average().toFloat(), idx, id)
        }.sortedBy { it.z }

        for (face in ordered) {
            path.reset()
            face.indices.forEachIndexed { i, vi ->
                val sx = cx + projected[vi][0] * scale
                val sy = cy - projected[vi][1] * scale
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            path.close()

            when (face.id) {
                0 -> {
                    canvas.drawPath(path, bodyPaint)
                    drawQuad(
                        canvas, cx, cy, scale, screenLocal,
                        if (face.z > -0.02f) screenPaint else screenBackPaint,
                    )
                }
                1 -> canvas.drawPath(path, sidePaint)
                else -> canvas.drawPath(path, if (face.id <= 3) sidePaint else bodyPaint)
            }
            canvas.drawPath(path, edgePaint)
        }
    }

    private fun drawQuad(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        local: Array<FloatArray>,
        paint: Paint,
    ) {
        path.reset()
        local.forEachIndexed { i, c ->
            val p = project(c[0], c[1], c[2])
            val sx = cx + p[0] * scale
            val sy = cy - p[1] * scale
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * Matches OrientationTracker: upright = 0/0, pitch −90° = flat face-up,
     * +roll = right edge down.
     */
    private fun project(x: Float, y: Float, z: Float): FloatArray {
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val roll = Math.toRadians(-rollDeg.toDouble())
        val yaw = Math.toRadians(-yawDeg.toDouble())

        // Roll in the screen plane first, then pitch toward/away, then yaw twist
        var p = rotZ(x, y, z, roll)
        p = rotX(p[0], p[1], p[2], pitch)
        p = rotY(p[0], p[1], p[2], yaw)

        val persp = 1f / (1.55f - p[2] * 0.35f)
        return floatArrayOf(p[0] * persp, p[1] * persp, p[2])
    }

    private fun rotX(x: Float, y: Float, z: Float, rad: Double): FloatArray {
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(x, y * c - z * s, y * s + z * c)
    }

    private fun rotY(x: Float, y: Float, z: Float, rad: Double): FloatArray {
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(x * c + z * s, y, -x * s + z * c)
    }

    private fun rotZ(x: Float, y: Float, z: Float, rad: Double): FloatArray {
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(x * c - y * s, x * s + y * c, z)
    }
}
