package com.wad.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CalibrationActivity : AppCompatActivity() {
    private lateinit var touchView: CalibrationView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        touchView = CalibrationView(this, savedJson) { selectedLabel ->
            statusText.text = "Titik aktif: $selectedLabel — gunakan tombol panah di bawah untuk presisi 100%."
        }

        statusText = TextView(this).apply {
            text = "Geser 4 titik biru ke pojok papan, lalu haluskan dengan tombol panah di bawah."
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(32, 96, 32, 24)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 200, 32, 0)
        }

        val btnSquare = Button(this).apply {
            text = "Rata Persegi"
            setOnClickListener { touchView.snapToSquare() }
        }

        val btnSave = Button(this).apply {
            text = "Simpan Kalibrasi"
            setOnClickListener { savePoints(touchView.getPoints()) }
        }

        topBar.addView(btnSquare)
        topBar.addView(btnSave)

        // Panel tombol geser halus (2 piksel per ketuk) di bagian bawah layar
        val nudgeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(16, 16, 16, 48)
        }

        val btnLeft = Button(this).apply { text = "◄"; setOnClickListener { touchView.nudge(-2f, 0f) } }
        val btnUp = Button(this).apply { text = "▲"; setOnClickListener { touchView.nudge(0f, -2f) } }
        val btnDown = Button(this).apply { text = "▼"; setOnClickListener { touchView.nudge(0f, 2f) } }
        val btnRight = Button(this).apply { text = "►"; setOnClickListener { touchView.nudge(2f, 0f) } }

        nudgeBar.addView(btnLeft)
        nudgeBar.addView(btnUp)
        nudgeBar.addView(btnDown)
        nudgeBar.addView(btnRight)

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )

        layout.addView(touchView)
        layout.addView(statusText)
        layout.addView(topBar)
        layout.addView(nudgeBar, bottomParams)

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
    private val onSelected: (String) -> Unit
) : View(context) {

    private val points = mutableListOf<PointF>()
    private var selectedPointIndex = 0
    private var isDragging = false
    private var hasInitializedPoints = false

    private val bluePaint = Paint().apply {
        color = Color.parseColor("#1E88E5")
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val selectedPaint = Paint().apply {
        color = Color.parseColor("#00E676")
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
    private val gridPaint = Paint().apply {
        color = Color.argb(190, 0, 230, 118)
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
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
            val boardSize = (w * 0.90f)
            val left = (w - boardSize) / 2f
            val top = (h - boardSize) / 2f
            points.clear()
            points.add(PointF(left, top))
            points.add(PointF(left + boardSize, top))
            points.add(PointF(left + boardSize, top + boardSize))
            points.add(PointF(left, top + boardSize))
            hasInitializedPoints = true
        }
    }

    fun snapToSquare() {
        if (points.size < 4) return
        val left = min(points[0].x, points[3].x)
        val right = max(points[1].x, points[2].x)
        val top = min(points[0].y, points[1].y)
        val size = right - left
        points[0] = PointF(left, top)
        points[1] = PointF(left + size, top)
        points[2] = PointF(left + size, top + size)
        points[3] = PointF(left, top + size)
        invalidate()
    }

    fun nudge(dx: Float, dy: Float) {
        if (selectedPointIndex in 0..3) {
            val p = points[selectedPointIndex]
            points[selectedPointIndex] = PointF(
                (p.x + dx).coerceIn(0f, width.toFloat()),
                (p.y + dy).coerceIn(0f, height.toFloat())
            )
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 4) return

        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)

        // Gambar garis grid penuh 8x8 agar mudah disejajarkan dengan garis kotak papan
        for (i in 0..8) {
            val hStart = Homography.transformPoint(matrix, PointF(0f, i.toFloat()))
            val hEnd = Homography.transformPoint(matrix, PointF(8f, i.toFloat()))
            canvas.drawLine(hStart.x, hStart.y, hEnd.x, hEnd.y, gridPaint)

            val vStart = Homography.transformPoint(matrix, PointF(i.toFloat(), 0f))
            val vEnd = Homography.transformPoint(matrix, PointF(i.toFloat(), 8f))
            canvas.drawLine(vStart.x, vStart.y, vEnd.x, vEnd.y, gridPaint)
        }

        for (r in 0..7) {
            for (c in 0..7) {
                val pt = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                canvas.drawCircle(pt.x, pt.y, 6f, redDotPaint)
            }
        }

        for (i in 0..3) {
            val paint = if (i == selectedPointIndex) selectedPaint else bluePaint
            canvas.drawCircle(points[i].x, points[i].y, 40f, paint)
            canvas.drawCircle(points[i].x, points[i].y, 40f, whiteStroke)
            canvas.drawText(cornerLabels[i], points[i].x, points[i].y + 9f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                for (i in 0..3) {
                    val dist = hypot((event.x - points[i].x).toDouble(), (event.y - points[i].y).toDouble())
                    if (dist < 110.0) {
                        selectedPointIndex = i
                        isDragging = true
                        onSelected(cornerLabels[i])
                        invalidate()
                        break
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && selectedPointIndex in 0..3) {
                    points[selectedPointIndex] = PointF(
                        event.x.coerceIn(0f, width.toFloat()),
                        event.y.coerceIn(0f, height.toFloat())
                    )
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    fun getPoints(): List<PointF> = points
}
