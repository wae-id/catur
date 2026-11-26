package com.wad.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class OverlayCanvasView(context: Context) : View(context) {

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        textSize = 31f
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        color = Color.argb(130, 255, 60, 60)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#EB0D1117")
    }

    private val arrowPaint = Paint().apply {
        color = Color.argb(230, 0, 230, 118)
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val headPaint = Paint().apply {
        color = Color.argb(245, 0, 230, 118)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val arrowPath = Path()

    var fpsText = ""
    var bestMove = ""
    var evalText = ""
    var points: List<PointF>? = null
    var isFlipped = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val moveDisplay = if (bestMove.isEmpty()) {
            "Best Move: -"
        } else if (evalText.isNotEmpty()) {
            "Best Move: $bestMove ($evalText)"
        } else {
            "Best Move: $bestMove"
        }

        val boxRight = max(
            480f,
            max(textPaint.measureText(fpsText), textPaint.measureText(moveDisplay)) + 76f
        ).coerceAtMost(width - 20f)

        canvas.drawRoundRect(20f, 80f, boxRight, 196f, 16f, 16f, bgPaint)
        canvas.drawText(fpsText, 38f, 126f, textPaint)
        canvas.drawText(moveDisplay, 38f, 172f, textPaint)

        val calibPoints = points ?: return
        if (calibPoints.size != 4) return

        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, calibPoints)

        for (r in 0..7) {
            for (c in 0..7) {
                val center = Homography.transformPoint(matrix, PointF(c + 0.5f, r + 0.5f))
                canvas.drawCircle(center.x, center.y, 4f, dotPaint)
            }
        }

        if (bestMove.length >= 4) {
            var fromCol = bestMove[0] - 'a'
            var fromRow = 8 - Character.getNumericValue(bestMove[1])
            var toCol = bestMove[2] - 'a'
            var toRow = 8 - Character.getNumericValue(bestMove[3])

            if (fromCol !in 0..7 || fromRow !in 0..7 || toCol !in 0..7 || toRow !in 0..7) return

            if (isFlipped) {
                fromCol = 7 - fromCol
                fromRow = 7 - fromRow
                toCol = 7 - toCol
                toRow = 7 - toRow
            }

            val p1 = Homography.transformPoint(matrix, PointF(fromCol + 0.5f, fromRow + 0.5f))
            val p2 = Homography.transformPoint(matrix, PointF(toCol + 0.5f, toRow + 0.5f))

            drawDirectionalArrow(canvas, p1.x, p1.y, p2.x, p2.y)
        }
    }

    private fun drawDirectionalArrow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {
        val distance = hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
        if (distance < 10f) return

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val headLength = 38f
        val headAngle = Math.toRadians(28.0)

        val lineEndX = (endX - (headLength * 0.75f) * cos(angle)).toFloat()
        val lineEndY = (endY - (headLength * 0.75f) * sin(angle)).toFloat()
        canvas.drawLine(startX, startY, lineEndX, lineEndY, arrowPaint)

        val x1 = (endX - headLength * cos(angle - headAngle)).toFloat()
        val y1 = (endY - headLength * sin(angle - headAngle)).toFloat()
        val x2 = (endX - headLength * cos(angle + headAngle)).toFloat()
        val y2 = (endY - headLength * sin(angle + headAngle)).toFloat()

        arrowPath.reset()
        arrowPath.moveTo(endX, endY)
        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()

        canvas.drawPath(arrowPath, headPaint)
    }
}
