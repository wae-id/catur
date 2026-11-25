package com.wad.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

class CalibrationActivity : AppCompatActivity() {
    private lateinit var touchView: CalibrationView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pastikan jendela transparan dan koordinat (0,0) sejajar penuh dengan layar fisik
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#33000000"))
        }

        val prefs = getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("calibration_points", null)

        touchView = CalibrationView(this, savedJson) {
            statusText.text = "Pasang 4 titik biru di sudut LUAR papan (TL, TR, BR, BL)."
        }

        statusText = TextView(this).apply {
            text = "Pasang 4 titik biru di sudut LUAR papan (TL, TR, BR, BL)."
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(32, 96, 32, 24)
        }

        val btnSave = Button(this).apply {
            text = "Simpan Kalibrasi"
            setOnClickListener {
                savePoints(touchView.getPoints())
            }
        }

        layout.addView(touchView)
        layout.addView(statusText)

        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 190
            leftMargin = 40
        }
        layout.addView(btnSave, btnParams)

        setContentView(layout)
    }

    private fun savePoints(points: List<PointF>) {
        val prefs = getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("x", p.x.toDouble())
            obj.put("y", p.y.toDouble())
            jsonArray.put(obj)
        }
        prefs.edit().putString("calibration_points", jsonArray.toString()).apply()
        finish()
    }
}

class CalibrationView(
    context: Context,
    savedJson: String?,
    private val onMoved: () -> Unit
) : View(context) {

    private val points = mutableListOf<PointF>()
    private var activePointIndex = -1
    private var hasInitializedPoints = false

    private val bluePaint = Paint().apply {
        color = Color.BLUE
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val whiteStroke = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val redDotPaint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.GREEN
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val cornerLabels = arrayOf("TL", "TR", "BR", "BL")

    init {
        if (savedJson != null) {
            try {
                val jsonArray = JSONArray(savedJson)
                if (jsonArray.length() == 4) {
                    for (i in 0 until 4) {
                        val obj = jsonArray.getJSONObject(i)
                        points.add(PointF(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat()))
                    }
                    hasInitializedPoints = true
                }
            } catch (_: Exception) {
                points.clear()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasInitializedPoints && w > 0 && h > 0) {
            val boardSize = (w * 0.85f)
            val left = (w - boardSize) / 2f
            val top = (h - boardSize) / 2f
            points.clear()
            points.add(PointF(left, top))                         // TL (Kiri Atas)
            points.add(PointF(left + boardSize, top))             // TR (Kanan Atas)
            points.add(PointF(left + boardSize, top + boardSize)) // BR (Kanan Bawah)
            points.add(PointF(left, top + boardSize))             // BL (Kiri Bawah)
            hasInitializedPoints = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 4) return

        canvas.drawLine(points[0].x, points[0].y, points[1].x, points[1].y, linePaint)
        canvas.drawLine(points[1].x, points[1].y, points[2].x, points[2].y, linePaint)
        canvas.drawLine(points[2].x, points[2].y, points[3].x, points[3].y, linePaint)
        canvas.drawLine(points[3].x, points[3].y, points[0].x, points[0].y, linePaint)

        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)
        for (r in 0..7) {
            for (c in 0..7) {
                val pt = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                canvas.drawCircle(pt.x, pt.y, 7f, redDotPaint)
            }
        }

        for (i in 0..3) {
            canvas.drawCircle(points[i].x, points[i].y, 42f, bluePaint)
            canvas.drawCircle(points[i].x, points[i].y, 42f, whiteStroke)
            canvas.drawText(cornerLabels[i], points[i].x, points[i].y + 9f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                for (i in 0..3) {
                    val dist = hypot((event.x - points[i].x).toDouble(), (event.y - points[i].y).toDouble())
                    if (dist < 110.0) {
                        activePointIndex = i
                        break
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointIndex != -1) {
                    points[activePointIndex] = PointF(
                        event.x.coerceIn(0f, width.toFloat()),
                        event.y.coerceIn(0f, height.toFloat())
                    )
                    invalidate()
                    onMoved()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointIndex = -1
            }
        }
        return true
    }

    fun getPoints(): List<PointF> = points
}
