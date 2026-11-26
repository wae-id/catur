package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class BoardReader(private val context: Context) {

    private val chessLogic = ChessLogic()
    var isUserPlayingBlack = false
    var detectedPoints: List<PointF>? = null
    private var missedBoardFrames = 0

    init {
        detectedPoints = loadManualCalibration()
    }

    fun loadManualCalibration(): List<PointF>? {
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

    fun isBoardReady(): Boolean = true

    fun resetGame() {
        chessLogic.reset()
        detectedPoints = loadManualCalibration()
        missedBoardFrames = 0
    }

    fun scanFullBoard(screenBitmap: Bitmap): String {
        // 1. Jika papan belum terkunci, jalankan deteksi otomatis 8x8
        if (detectedPoints == null) {
            detectedPoints = autoDetectBoard(screenBitmap)
        }

        val points = detectedPoints ?: return ""
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)

        val detectedBoard = Array(8) { CharArray(8) { '.' } }
        var pieceCount = 0

        for (row in 0..7) {
            for (col in 0..7) {
                val p = detectSquarePiece(screenBitmap, matrix, row, col)
                detectedBoard[row][col] = p
                if (p != '.') pieceCount++
            }
        }

        // Jika papan tiba-tiba kosong (misal pindah menu/iklan), scan ulang posisi papan
        if (pieceCount < 2) {
            missedBoardFrames++
            if (missedBoardFrames >= 4) {
                detectedPoints = autoDetectBoard(screenBitmap)
                missedBoardFrames = 0
            }
            return ""
        }
        missedBoardFrames = 0

        chessLogic.updateFromScan(detectedBoard, isUserPlayingBlack)

        val turn = if (isUserPlayingBlack) "b" else "w"
        return chessLogic.toFEN(turn)
    }

    /**
     * Mendeteksi koordinat 4 sudut papan catur 8x8 secara otomatis dari pola 49 perpotongan petak.
     */
    private fun autoDetectBoard(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 300 || h < 400) return null

        val minSize = (w * 0.70f).toInt()
        val maxSize = w
        val maxLeft = (w * 0.18f).toInt()
        val minTop = (h * 0.10f).toInt()
        val maxTop = (h * 0.58f).toInt()

        var bestScore = 0.0
        var bestLeft = 0
        var bestTop = 0
        var bestSize = 0

        // Tahap 1: Pencarian cepat (coarse grid)
        for (size in minSize..maxSize step 10) {
            val leftLimit = minOf(maxLeft, w - size)
            for (left in 0..leftLimit step 8) {
                val topLimit = minOf(maxTop, h - size)
                for (top in minTop..topLimit step 10) {
                    val score = scoreBoardCandidate(bitmap, left.toFloat(), top.toFloat(), size.toFloat(), coarse = true)
                    if (score > bestScore) {
                        bestScore = score
                        bestLeft = left
                        bestTop = top
                        bestSize = size
                    }
                }
            }
        }

        if (bestScore < 12.0 || bestSize == 0) return null

        // Tahap 2: Penghalusan presisi hingga 2 piksel di sekitar kandidat terbaik
        var fineBestScore = -1.0
        var fineLeft = bestLeft
        var fineTop = bestTop
        var fineSize = bestSize

        for (size in (bestSize - 12)..(bestSize + 12) step 2) {
            if (size < minSize || size > w) continue
            for (left in (bestLeft - 10)..(bestLeft + 10) step 2) {
                if (left < 0 || left + size > w) continue
                for (top in (bestTop - 10)..(bestTop + 10) step 2) {
                    if (top < 0 || top + size > h) continue
                    val score = scoreBoardCandidate(bitmap, left.toFloat(), top.toFloat(), size.toFloat(), coarse = false)
                    if (score > fineBestScore) {
                        fineBestScore = score
                        fineLeft = left
                        fineTop = top
                        fineSize = size
                    }
                }
            }
        }

        if (fineBestScore < 18.0) return null

        val l = fineLeft.toFloat()
        val t = fineTop.toFloat()
        val r = (fineLeft + fineSize).toFloat()
        val b = (fineTop + fineSize).toFloat()

        return listOf(
            PointF(l, t), // TL
            PointF(r, t), // TR
            PointF(r, b), // BR
            PointF(l, b)  // BL
        )
    }

    private fun scoreBoardCandidate(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        size: Float,
        coarse: Boolean
    ): Double {
        val cell = size / 8f
        val d = (cell * 0.14f).coerceAtLeast(3f)

        // Uji cepat 4 titik perpotongan tengah untuk menolak kandidat non-papan seketika
        var expectedSign = 0
        var quickHits = 0
        for (row in 3..4) {
            for (col in 3..4) {
                val ix = left + col * cell
                val iy = top + row * cell
                val diff = cornerContrast(bitmap, ix, iy, d)
                val parity = if ((row + col) % 2 == 0) 1 else -1
                if (abs(diff) < 18.0) return -1.0
                val s = if (diff > 0) parity else -parity
                if (expectedSign == 0) {
                    expectedSign = s
                    quickHits++
                } else if (s == expectedSign) {
                    quickHits++
                } else {
                    return -1.0
                }
            }
        }
        if (quickHits < 4) return -1.0

        val step = if (coarse) 2 else 1
        var totalScore = 0.0
        var validNodes = 0

        for (row in 1..7 step step) {
            for (col in 1..7 step step) {
                val ix = left + col * cell
                val iy = top + row * cell
                val diff = cornerContrast(bitmap, ix, iy, d)
                val parity = if ((row + col) % 2 == 0) 1 else -1
                val signedDiff = diff * parity * expectedSign

                if (signedDiff > 16.0) {
                    totalScore += minOf(signedDiff, 90.0) / 30.0
                    validNodes++
                } else if (signedDiff < -10.0) {
                    totalScore -= 2.0
                }
            }
        }

        if (coarse) return if (validNodes >= 9) totalScore else -1.0

        // Penalti jika pola papan catur masih berlanjut di luar batas (mencegah pergeseran 1 kotak)
        val outerOffsets = arrayOf(
            left - cell * 0.5f to top + cell * 3.5f,
            left + size + cell * 0.5f to top + cell * 3.5f,
            left + cell * 3.5f to top - cell * 0.5f,
            left + cell * 3.5f to top + size + cell * 0.5f
        )
        for ((ox, oy) in outerOffsets) {
            val px = ox.roundToInt()
            val py = oy.roundToInt()
            if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                val lum = pixelLum(bitmap.getPixel(px, py))
                // Bingkai/latar di luar papan umumnya seragam; penalti ringan jika melenceng
                if (lum in 175.0..235.0) totalScore -= 0.5
            }
        }

        return if (validNodes >= 34) totalScore else -1.0
    }

    private fun cornerContrast(bitmap: Bitmap, ix: Float, iy: Float, d: Float): Double {
        val x1 = (ix - d).roundToInt()
        val y1 = (iy - d).roundToInt()
        val x2 = (ix + d).roundToInt()
        val y2 = (iy + d).roundToInt()

        if (x1 < 0 || y1 < 0 || x2 >= bitmap.width || y2 >= bitmap.height) return 0.0

        val tl = pixelLum(bitmap.getPixel(x1, y1))
        val tr = pixelLum(bitmap.getPixel(x2, y1))
        val bl = pixelLum(bitmap.getPixel(x1, y2))
        val br = pixelLum(bitmap.getPixel(x2, y2))

        // Pastikan sudut diagonal memiliki warna kotak yang konsisten
        if (abs(tl - br) > 35.0 || abs(tr - bl) > 35.0) return 0.0

        return ((tl + br) - (tr + bl)) * 0.5
    }

    private fun pixelLum(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

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

                    // Bidak putih memiliki warna putih netral (warmth < 32), berbeda dengan kayu krem (warmth > 55)
                    if (lum > 190.0 && warmth < 32) {
                        neutralWhiteCount++
                        whiteMask[gy][gx] = true
                    }

                    if (lum < 110.0) {
                        darkOutlineCount++
                        darkMask[gy][gx] = true
                    }

                    if (lum < 88.0) {
                        veryDarkCount++
                    }
                }
            }
        }

        val isWhitePiece = (neutralWhiteCount >= 9) && (darkOutlineCount >= 3 || neutralWhiteCount >= 14)
        val isBlackPiece = !isWhitePiece && (veryDarkCount >= 15)

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
