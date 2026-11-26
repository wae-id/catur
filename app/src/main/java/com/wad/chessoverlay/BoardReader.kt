package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max

class BoardReader(private val context: Context) {

    private val chessLogic = ChessLogic()
    var isUserPlayingBlack = false

    fun loadCalibration(): List<PointF>? {
        val prefs = context.getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("calibration_points", null) ?: return null

        return try {
            val jsonArray = JSONArray(jsonString)
            val points = mutableListOf<PointF>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                points.add(PointF(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat()))
            }
            if (points.size == 4) points else null
        } catch (e: Exception) {
            null
        }
    }

    fun isBoardReady(): Boolean = loadCalibration() != null

    fun resetGame() {
        chessLogic.reset()
    }

    fun scanFullBoard(screenBitmap: Bitmap): String {
        val points = loadCalibration() ?: return ""
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)

        val detectedBoard = Array(8) { CharArray(8) { '.' } }

        for (row in 0..7) {
            for (col in 0..7) {
                detectedBoard[row][col] = detectSquarePiece(screenBitmap, matrix, row, col)
            }
        }

        chessLogic.updateFromScan(detectedBoard, isUserPlayingBlack)

        val turn = if (isUserPlayingBlack) "b" else "w"
        return chessLogic.toFEN(turn)
    }

    private fun detectSquarePiece(
        bitmap: Bitmap,
        matrix: FloatArray,
        row: Int,
        col: Int
    ): Char {
        val gridSize = 14
        val whiteMask = Array(gridSize) { BooleanArray(gridSize) }
        val darkMask = Array(gridSize) { BooleanArray(gridSize) }

        var neutralWhiteCount = 0
        var pureBrightWhiteCount = 0
        var darkOutlineCount = 0
        var veryDarkCount = 0

        for (gy in 0 until gridSize) {
            val v = 0.16f + (gy.toFloat() / (gridSize - 1)) * 0.68f
            for (gx in 0 until gridSize) {
                val u = 0.16f + (gx.toFloat() / (gridSize - 1)) * 0.68f
                val pt = Homography.transformPoint(matrix, PointF(col + u, row + v))
                val px = pt.x.toInt()
                val py = pt.y.toInt()

                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                    val c = bitmap.getPixel(px, py)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)

                    val lum = 0.299 * r + 0.587 * g + 0.114 * b
                    val warmth = abs(r - b)

                    // Bidak putih memiliki warna putih netral (bukan kayu krem atau sorotan kuning)
                    if (lum > 180.0 && warmth < 28) {
                        neutralWhiteCount++
                        whiteMask[gy][gx] = true
                        if (lum > 235.0 && warmth < 16) {
                            pureBrightWhiteCount++
                        }
                    }

                    // Garis tepi gelap (ada pada bidak putih maupun hitam)
                    if (lum < 110.0) {
                        darkOutlineCount++
                        darkMask[gy][gx] = true
                    }

                    // Isi gelap pekat (khusus bidak hitam)
                    if (lum < 82.0) {
                        veryDarkCount++
                    }
                }
            }
        }

        val isWhitePiece = (neutralWhiteCount >= 9 && darkOutlineCount >= 5) ||
            (pureBrightWhiteCount >= 12)
        val isBlackPiece = !isWhitePiece && (veryDarkCount >= 14)

        if (!isWhitePiece && !isBlackPiece) return '.'

        val activeMask = if (isWhitePiece) whiteMask else darkMask
        val pieceType = classifySilhouette(activeMask)
        return if (isWhitePiece) pieceType.uppercaseChar() else pieceType.lowercaseChar()
    }

    private fun classifySilhouette(mask: Array<BooleanArray>): Char {
        val size = mask.size
        var minY = size
        var maxY = -1
        val rowWidths = IntArray(size)
        val rowSegments = IntArray(size)

        for (y in 0 until size) {
            var count = 0
            var segments = 0
            var inSeg = false
            for (x in 0 until size) {
                if (mask[y][x]) {
                    count++
                    if (!inSeg) {
                        segments++
                        inSeg = true
                    }
                } else {
                    inSeg = false
                }
            }
            rowWidths[y] = count
            rowSegments[y] = segments
            if (count > 0) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (maxY < minY) return 'P'
        val height = maxY - minY + 1
        if (height <= 7) return 'P'

        val topWidth = max(rowWidths[minY], rowWidths[(minY + 1).coerceAtMost(maxY)])
        val midY = minY + height / 2
        val midWidth = rowWidths[midY]
        val maxSegmentsTop = max(rowSegments[minY], rowSegments[(minY + 1).coerceAtMost(maxY)])

        return when {
            maxSegmentsTop >= 2 && topWidth >= 5 -> 'Q'
            topWidth >= 5 && height in 8..10 -> 'R'
            height >= 11 && topWidth <= 4 -> 'K'
            height >= 10 && midWidth >= 5 && topWidth <= 4 -> 'B'
            midWidth >= 6 && topWidth in 3..5 -> 'N'
            else -> 'P'
        }
    }
}
