package com.wad.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class CalibrationActivity : AppCompatActivity() {
    private lateinit var touchView: CalibrationView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = FrameLayout(this)
        
        // Load titik sebelumnya kalau ada, kalau engga bikin kotak default di tengah
        val prefs = getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("calibration_points", null)
        
        touchView = CalibrationView(this, savedJson) {
            statusText.text = "Geser 4 titik biru ke pojok LUAR papan."
        }
        
        statusText = TextView(this).apply {
            text = "Geser 4 titik biru ke pojok LUAR papan."
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
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
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 100; leftMargin = 50 }
        layout.addView(btnSave, btnParams)

        setContentView(layout)
    }

    private fun savePoints(points: List<PointF>) {
        val prefs = getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("x", p.x)
            obj.put("y", p.y)
            jsonArray.put(obj)
        }
        prefs.edit().putString("calibration_points", jsonArray.toString()).apply()
        finish()
    }
}

class CalibrationView(context: Context, savedJson: String?, private val onMoved: () -> Unit) : View(context) {
    private val points = mutableListOf<PointF>()
    private var activePointIndex = -1
    
    private val bluePaint = Paint().apply { color = Color.BLUE; isAntiAlias = true; style = Paint.Style.FILL }
    private val whiteStroke = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val redDotPaint = Paint().apply { color = Color.RED; isAntiAlias = true; style = Paint.Style.FILL }
    private val linePaint = Paint().apply { color = Color.GREEN; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 3f }

    init {
        if (savedJson != null) {
            try {
                val jsonArray = JSONArray(savedJson)
                for (i in 0 until 4) {
                    val obj = jsonArray.getJSONObject(i)
                    points.add(PointF(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat()))
                }
            } catch (e: Exception) {
                createDefaultPoints()
            }
        } else {
            createDefaultPoints()
        }
    }

    private fun createDefaultPoints() {
        points.clear()
        // Bikin kotak default di tengah layar
        points.add(PointF(200f, 500f))   // Kiri Atas
        points.add(PointF(600f, 500f))   // Kanan Atas
        points.add(PointF(600f, 1000f))  // Kanan Bawah
        points.add(PointF(200f, 1000f))  // Kiri Bawah
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (points.size < 4) return
        
        // Gambar garis kotak
        canvas.drawLine(points[0].x, points[0].y, points[1].x, points[1].y, linePaint)
        canvas.drawLine(points[1].x, points[1].y, points[2].x, points[2].y, linePaint)
        canvas.drawLine(points[2].x, points[2].y, points[3].x, points[3].y, linePaint)
        canvas.drawLine(points[3].x, points[3].y, points[0].x, points[0].y, linePaint)

        // Gambar 64 titik merah preview papan
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)
        for (r in 0..7) {
            for (c in 0..7) {
                val pt = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                canvas.drawCircle(pt.x, pt.y, 8f, redDotPaint)
            }
        }

        // Gambar 4 titik handle (biru)
        for (i in 0..3) {
            canvas.drawCircle(points[i].x, points[i].y, 40f, bluePaint)
            canvas.drawCircle(points[i].x, points[i].y, 40f, whiteStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Cari titik mana yang disentuh (jarak < 100px)
                for (i in 0..3) {
                    val dx = event.x - points[i].x
                    val dy = event.y - points[i].y
                    if (Math.sqrt((dx * dx + dy * dy).toDouble()) < 100.0) {
                        activePointIndex = i
                        break
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointIndex != -1) {
                    points[activePointIndex] = PointF(event.x, event.y)
                    invalidate() // Update gambar
                    onMoved()
                }
            }
            MotionEvent.ACTION_UP -> {
                activePointIndex = -1
            }
        }
        return true
    }

    fun getPoints(): List<PointF> = points
}
