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
    
    // Paint buat panah rekomendasi
    private val arrowPaint = Paint().apply { 
        color = Color.CYAN; strokeWidth = 12f; style = Paint.Style.STROKE; isAntiAlias = true 
    }

    var fpsText = ""
    var boardText = ""
    var bestMove = ""
    var points: List<PointF>? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background kotak item buat teks
        canvas.drawRect(20f, 20f, 450f, 450f, bgPaint)

        // Teks FPS & Papan
        var y = 60f
        canvas.drawText(fpsText, 30f, y, textPaint)
        y += 40f
        boardText.lines().forEach { line ->
            canvas.drawText(line, 30f, y, textPaint)
            y += 35f
        }

        if (points != null && points!!.size == 4) {
            val src = listOf(PointF(0f,0f), PointF(8f,0f), PointF(8f,8f), PointF(0f,8f))
            val matrix = Homography.getPerspectiveTransform(src, points!!)
            
            // Gambar 64 titik merah
            for (r in 0..7) {
                for (c in 0..7) {
                    val center = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                    canvas.drawCircle(center.x, center.y, 6f, dotPaint)
                }
            }

            // GAMBAR PANAH DARI STOCKFISH
            if (bestMove.length >= 4) {
                // Ubah e2e4 jadi koordinat papan
                val fromCol = bestMove[0] - 'a'
                val fromRow = 8 - Character.getNumericValue(bestMove[1])
                val toCol = bestMove[2] - 'a'
                val toRow = 8 - Character.getNumericValue(bestMove[3])

                val p1 = Homography.transformPoint(matrix, PointF(fromCol + 0.5f, fromRow + 0.5f))
                val p2 = Homography.transformPoint(matrix, PointF(toCol + 0.5f, toRow + 0.5f))

                // Gambar garis panah
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, arrowPaint)
                // Gambar ujung panah (lingkaran)
                canvas.drawCircle(p2.x, p2.y, 20f, Paint().apply { color = Color.CYAN; isAntiAlias = true })
            }
        }
    }
}
