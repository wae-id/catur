package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.sqrt

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
        // 1. Ambil warna latar asli kotak dari 4 sudut kotak
        val cornerOffsets = arrayOf(
            0.10f to 0.10f,
            0.90f to 0.10f,
            0.10f to 0.90f,
            0.90f to 0.90f
        )

        var bgR = 0.0
        var bgG = 0.0
        var bgB = 0.0
        var bgCount = 0

        for ((ox, oy) in cornerOffsets) {
            val pt = Homography.transformPoint(matrix, PointF(col + ox, row + oy))
            val px = pt.x.toInt()
            val py = pt.y.toInt()
            if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                val c = bitmap.getPixel(px, py)
                bgR += Color.red(c)
                bgG += Color.green(c)
                bgB += Color.blue(c)
                bgCount++
            }
        }

        if (bgCount == 0) return '.'
        bgR /= bgCount
        bgG /= bgCount
        bgB /= bgCount

        // 2. Pindai siluet bidak di bagian dalam kotak (grid 12x12)
        val gridSize = 12
        val mask = Array(gridSize) { BooleanArray(gridSize) }
        val luminances = mutableListOf<Double>()

        for (gy in 0 until gridSize) {
            val v = 0.16f + (gy.toFloat() / (gridSize - 1)) * 0.68f
            for (gx in 0 until gridSize) {
                val u = 0.16f + (gx.toFloat() / (gridSize - 1)) * 0.68f
                val pt = Homography.transformPoint(matrix, PointF(col + u, row + v))
                val px = pt.x.toInt()
                val py = pt.y.toInt()
                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                    val c = bitmap.getPixel(px, py)
                    val r = Color.red(c).toDouble()
                    val g = Color.green(c).toDouble()
                    val b = Color.blue(c).toDouble()

                    val dist = sqrt(
                        (r - bgR) * (r - bgR) +
                        (g - bgG) * (g - bgG) +
                        (b - bgB) * (b - bgB)
                    )

                    if (dist > 38.0) {
                        mask[gy][gx] = true
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        luminances.add(lum)
                    }
                }
            }
        }

        // Jika piksel berbeda < 14 dari 144 titik, kotak dianggap kosong
        if (luminances.size < 14) return '.'

        // 3. Tentukan warna bidak dari persentil ke-75 kecerahan (mengabaikan garis tepi hitam)
        luminances.sort()
        val p75Lum = luminances[(luminances.size * 0.75).toInt().coerceIn(0, luminances.lastIndex)]
        val isWhite = p75Lum > 145.0

        // 4. Klasifikasi bentuk siluet bidak
        val pieceType = classifySilhouette(mask)
        return if (isWhite) pieceType.uppercaseChar() else pieceType.lowercaseChar()
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
        if (height <= 6) return 'P'

        val topWidth = max(rowWidths[minY], rowWidths[(minY + 1).coerceAtMost(maxY)])
        val midY = minY + height / 2
        val midWidth = rowWidths[midY]
        val baseWidth = max(rowWidths[maxY], rowWidths[(maxY - 1).coerceAtLeast(minY)])
        val maxSegmentsTop = max(rowSegments[minY], rowSegments[(minY + 1).coerceAtMost(maxY)])

        return when {
            maxSegmentsTop >= 2 && topWidth >= 5 -> 'Q'
            topWidth >= 5 && height in 7..9 -> 'R'
            height >= 10 && topWidth <= 3 -> 'K'
            height >= 9 && midWidth >= 5 && topWidth <= 4 -> 'B'
            midWidth >= 6 && topWidth in 3..5 -> 'N'
            else -> 'P'
        }
    }
}
