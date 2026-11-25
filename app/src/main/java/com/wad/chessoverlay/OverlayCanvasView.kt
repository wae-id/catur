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
    
    private val arrowPaint = Paint().apply { 
        color = Color.MAGENTA; strokeWidth = 15f; style = Paint.Style.STROKE; isAntiAlias = true 
    }
    private val headPaint = Paint().apply { 
        color = Color.MAGENTA; style = Paint.Style.FILL; isAntiAlias = true 
    }

    var fpsText = ""
    var bestMove = ""
    var points: List<PointF>? = null
    var isFlipped = false // <-- Variabel buat nentuin papan kebalik atau engga

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.drawRect(20f, 20f, 450f, 150f, bgPaint)
        canvas.drawText(fpsText, 30f, 60f, textPaint)
        canvas.drawText("Best Move: $bestMove", 30f, 110f, textPaint)

        if (points != null && points!!.size == 4) {
            val src = listOf(PointF(0f,0f), PointF(8f,0f), PointF(8f,8f), PointF(0f,8f))
            val matrix = Homography.getPerspectiveTransform(src, points!!)
            
            for (r in 0..7) {
                for (c in 0..7) {
                    val center = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                    canvas.drawCircle(center.x, center.y, 6f, dotPaint)
                }
            }

            if (bestMove.length >= 4) {
                var fromCol = bestMove[0] - 'a'
                var fromRow = 8 - Character.getNumericValue(bestMove[1])
                var toCol = bestMove[2] - 'a'
                var toRow = 8 - Character.getNumericValue(bestMove[3])

                // Kalau papan kebalik (main hitam), balikin koordinat panahnya
                if (isFlipped) {
                    fromCol = 7 - fromCol
                    fromRow = 7 - fromRow
                    toCol = 7 - toCol
                    toRow = 7 - toRow
                }

                val p1 = Homography.transformPoint(matrix, PointF(fromCol + 0.5f, fromRow + 0.5f))
                val p2 = Homography.transformPoint(matrix, PointF(toCol + 0.5f, toRow + 0.5f))

                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, arrowPaint)
                canvas.drawCircle(p2.x, p2.y, 25f, headPaint)
            }
        }
    }
}
