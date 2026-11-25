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
        touchView = CalibrationView(this) { count ->
            if (count < 4) {
                statusText.text = "Tap 4 pojok LUAR papan ($count/4)"
            } else {
                statusText.text = "Cek titik merah. Jika pas, klik Simpan. Jika meleset, tap lagi untuk ulang."
            }
        }
        
        statusText = TextView(this).apply {
            text = "Tap pojok luar (a8, h8, h1, a1)"
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
        }

        val btnSave = Button(this).apply {
            text = "Simpan Kalibrasi"
            setOnClickListener {
                if (touchView.getPoints().size == 4) {
                    savePoints(touchView.getPoints())
                } else {
                    statusText.text = "Tap 4 titik dulu wad!"
                }
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

class CalibrationView(context: Context, private val onPointsChanged: (Int) -> Unit) : View(context) {
    private val points = mutableListOf<PointF>()
    private val paint = Paint().apply { color = Color.RED; isAntiAlias = true; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply { color = Color.GREEN; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 50f; textAlign = Paint.Align.CENTER }
    
    // Paint buat preview titik merah
    private val dotPaint = Paint().apply { color = Color.RED; isAntiAlias = true; style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        for (i in points.indices) {
            canvas.drawCircle(points[i].x, points[i].y, 40f, paint)
            canvas.drawCircle(points[i].x, points[i].y, 40f, strokePaint)
            canvas.drawText("${i + 1}", points[i].x, points[i].y + 15, textPaint)
        }
        
        // FITUR BARU: Kalau udah 4 titik, gambar preview grid 8x8!
        if (points.size == 4) {
            val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
            val matrix = Homography.getPerspectiveTransform(src, points)
            for (r in 0..7) {
                for (c in 0..7) {
                    val pt = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                    canvas.drawCircle(pt.x, pt.y, 6f, dotPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (points.size >= 4) points.clear() // Tap lagi buat ngulang
            points.add(PointF(event.x, event.y))
            onPointsChanged(points.size)
            invalidate()
        }
        return true
    }

    fun getPoints(): List<PointF> = points
}
