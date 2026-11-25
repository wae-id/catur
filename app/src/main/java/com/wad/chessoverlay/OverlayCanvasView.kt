package com.wad.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

class OverlayCanvasView(context: Context) : View(context) {
    private val textPaint = Paint().apply { color = Color.GREEN; textSize = 35f; isAntiAlias = true }
    private val dotPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }
    private val bgPaint = Paint().apply { color = Color.parseColor("#AA000000") }

    var fpsText = ""
    var boardText = ""
    var points: List<PointF>? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background kotak item buat teks
        canvas.drawRect(20f, 20f, 400f, 400f, bgPaint)

        // Teks FPS & Papan
        var y = 60f
        canvas.drawText(fpsText, 30f, y, textPaint)
        y += 40f
        boardText.lines().forEach { line ->
            canvas.drawText(line, 30f, y, textPaint)
            y += 35f
        }

        // Gambar 64 titik merah buat ngecek kalibrasi
        if (points != null && points!!.size == 4) {
            val src = listOf(PointF(0f,0f), PointF(8f,0f), PointF(8f,8f), PointF(0f,8f))
            val matrix = Homography.getPerspectiveTransform(src, points!!)
            for (r in 0..7) {
                for (c in 0..7) {
                    val center = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                    canvas.drawCircle(center.x, center.y, 6f, dotPaint)
                }
            }
        }
    }
}
