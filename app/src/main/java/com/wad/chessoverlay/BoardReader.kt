package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

class BoardReader(private val context: Context) {

    private val chessLogic = ChessLogic()
    var autoDetectSide = true
    var isUserPlayingBlack = false
    var detectedPoints: List<PointF>? = null
    var statusNote = ""
    private var invalidBoardStreak = 0

    init {
        detectedPoints = loadManualCalibration()
    }

    private fun loadManualCalibration(): List<PointF>? {
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
        invalidBoardStreak = 0
        statusNote = ""
    }

    fun scanFullBoard(screenBitmap: Bitmap): String {
        if (detectedPoints == null) {
            detectedPoints = autoDetectBoard(screenBitmap)
        }

        val points = detectedPoints ?: run {
            statusNote = "Mencari papan..."
            return ""
        }

        val l = points[0].x
        val t = points[0].y
        val boardSize = points[1].x - points[0].x

        if (boardSize > 100f && isPopupCoveringBoard(screenBitmap, l, t, boardSize)) {
            statusNote = "Papan tertutup..."
            return ""
        }

        // Validasi bahwa batas atas & bawah papan masih sesuai; jika bergeser, cari ulang otomatis
        if (boardSize > 100f && loadManualCalibration() == null &&
            !verifyBoardBounds(screenBitmap, l, t, boardSize)
        ) {
            invalidBoardStreak++
            if (invalidBoardStreak >= 4) {
                detectedPoints = autoDetectBoard(screenBitmap)
                invalidBoardStreak = 0
            }
            statusNote = "Menyelaraskan papan..."
            return ""
        }
        invalidBoardStreak = 0

        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)

        val detectedBoard = Array(8) { CharArray(8) { '.' } }
        val highlightedSquares = mutableListOf<Pair<Int, Int>>()
        var pieceCount = 0

        for (row in 0..7) {
            for (col in 0..7) {
                val (piece, isHighlighted) = analyzeSquare(screenBitmap, matrix, row, col)
                detectedBoard[row][col] = piece
                if (piece != '.') pieceCount++
                if (isHighlighted) highlightedSquares.add(row to col)
            }
        }

        if (pieceCount < 2) {
            statusNote = "Mencari papan..."
            return ""
        }

        if (autoDetectSide) {
            val detectedBlackSide = detectSideAutomatically(detectedBoard)
            if (detectedBlackSide != null && detectedBlackSide != isUserPlayingBlack) {
                isUserPlayingBlack = detectedBlackSide
                chessLogic.reset()
            }
        }

        val detectedTurnFromHighlight = inferTurnFromHighlights(detectedBoard, highlightedSquares)

        val orientedHighlights = highlightedSquares.map { (r, c) ->
            if (isUserPlayingBlack) (7 - r) to (7 - c) else r to c
        }.toSet()

        chessLogic.updateFromScan(
            detectedBoard,
            isUserPlayingBlack,
            detectedTurnFromHighlight,
            orientedHighlights
        )

        val myTurn = if (isUserPlayingBlack) "b" else "w"
        val activeTurn = chessLogic.currentTurn

        if (activeTurn != myTurn) {
            statusNote = "Menunggu lawan..."
            return ""
        }

        val fen = chessLogic.toFEN(myTurn)
        if (fen.isEmpty()) {
            statusNote = "Sinkronisasi posisi..."
            return ""
        }

        statusNote = ""
        return fen
    }

    /**
     * Memastikan tepi dalam atas dan bawah papan benar-benar berada di dalam area petak catur
     * dan tidak meleset ke latar belakang UI gelap.
     */
    private fun verifyBoardBounds(bitmap: Bitmap, left: Float, top: Float, size: Float): Boolean {
        val cell = size / 8f
        val topY = (top + 4f).roundToInt()
        val botY = (top + size - 4f).roundToInt()
        if (topY < 0 || botY >= bitmap.height) return false

        var warmHits = 0
        for (col in 0..7) {
            val x = (left + (col + 0.14f) * cell).roundToInt().coerceIn(0, bitmap.width - 1)
            if (isWarmBoardPixel(bitmap.getPixel(x, topY))) warmHits++
            if (isWarmBoardPixel(bitmap.getPixel(x, botY))) warmHits++
        }
        return warmHits >= 11
    }

    private fun isWarmBoardPixel(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 95.0 && r >= b - 8
    }

    private fun isDarkUiPixel(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum < 85.0
    }

    private fun isPopupCoveringBoard(bitmap: Bitmap, left: Float, top: Float, size: Float): Boolean {
        val cell = size / 8f
        var modalHits = 0

        for (r in 2..5) {
            for (c in 2..5) {
                val px = (left + (c + 0.15f) * cell).roundToInt()
                val py = (top + (r + 0.15f) * cell).roundToInt()
                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(px, py)
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    val lum = 0.299 * red + 0.587 * green + 0.114 * blue

                    if (blue > red + 10 && lum < 125.0) {
                        modalHits++
                    }
                }
            }
        }
        return modalHits >= 10
    }

    private fun detectSideAutomatically(rawBoard: Array<CharArray>): Boolean? {
        var topWhite = 0
        var topBlack = 0
        var bottomWhite = 0
        var bottomBlack = 0

        for (r in 0..1) {
            for (c in 0..7) {
                val p = rawBoard[r][c]
                if (p.isUpperCase()) topWhite++
                else if (p.isLowerCase()) topBlack++
            }
        }

        for (r in 6..7) {
            for (c in 0..7) {
                val p = rawBoard[r][c]
                if (p.isUpperCase()) bottomWhite++
                else if (p.isLowerCase()) bottomBlack++
            }
        }

        if (topWhite >= 8 && bottomBlack >= 8 && topWhite > topBlack + 4 && bottomBlack > bottomWhite + 4) {
            return true
        }
        if (topBlack >= 8 && bottomWhite >= 8 && topBlack > topWhite + 4 && bottomWhite > bottomBlack + 4) {
            return false
        }

        return null
    }

    private fun inferTurnFromHighlights(
        rawBoard: Array<CharArray>,
        highlights: List<Pair<Int, Int>>
    ): String? {
        if (highlights.size != 2) return null
        val (r1, c1) = highlights[0]
        val (r2, c2) = highlights[1]
        val p1 = rawBoard[r1][c1]
        val p2 = rawBoard[r2][c2]

        val landedPiece = when {
            p1 == '.' && p2 != '.' -> p2
            p2 == '.' && p1 != '.' -> p1
            else -> return null
        }

        return if (landedPiece.isUpperCase()) "b" else "w"
    }

    private fun analyzeSquare(
        bitmap: Bitmap,
        matrix: FloatArray,
        row: Int,
        col: Int
    ): Pair<Char, Boolean> {
        val bgOffsets = arrayOf(
            0.15f to 0.15f,
            0.85f to 0.15f,
            0.14f to 0.45f,
            0.86f to 0.45f,
            0.15f to 0.85f,
            0.85f to 0.85f
        )
        var yellowHits = 0

        for ((ox, oy) in bgOffsets) {
            val pt = Homography.transformPoint(matrix, PointF(col + ox, row + oy))
            val px = pt.x.toInt()
            val py = pt.y.toInt()
            if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                val c = bitmap.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val yellowScore = ((r + g) * 0.5) - b
                if (yellowScore > 74.0 && r > 152 && g > 138) {
                    yellowHits++
                }
            }
        }
        val isHighlighted = yellowHits >= 3

        val gridSize = 24
        val whiteMask = Array(gridSize) { BooleanArray(gridSize) }
        val darkMask = Array(gridSize) { BooleanArray(gridSize) }

        var neutralWhiteCount = 0
        var darkOutlineCount = 0
        var veryDarkCount = 0

        for (gy in 0 until gridSize) {
            val v = 0.13f + (gy.toFloat() / (gridSize - 1)) * 0.74f
            for (gx in 0 until gridSize) {
                val u = 0.13f + (gx.toFloat() / (gridSize - 1)) * 0.74f
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

                    if (lum > 188.0 && warmth < 34) {
                        neutralWhiteCount++
                        whiteMask[gy][gx] = true
                    }

                    if (lum < 112.0) {
                        darkOutlineCount++
                        darkMask[gy][gx] = true
                    }

                    if (lum < 90.0) {
                        veryDarkCount++
                    }
                }
            }
        }

        val isWhitePiece = (neutralWhiteCount >= 28) &&
            (neutralWhiteCount > veryDarkCount) &&
            (darkOutlineCount >= 8 || neutralWhiteCount >= 45)

        val isBlackPiece = !isWhitePiece &&
            (veryDarkCount >= 46) &&
            (veryDarkCount > neutralWhiteCount)

        if (!isWhitePiece && !isBlackPiece) return '.' to isHighlighted

        val activeMask = if (isWhitePiece) whiteMask else darkMask
        val pieceType = classifySilhouette24(activeMask)
        val pieceChar = if (isWhitePiece) pieceType.uppercaseChar() else pieceType.lowercaseChar()
        return pieceChar to isHighlighted
    }

    private fun classifySilhouette24(mask: Array<BooleanArray>): Char {
        val size = mask.size
        var minY = size
        var maxY = -1
        val rowWidths = IntArray(size)
        val rowSegments = IntArray(size)
        var maxWidth = 0

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
            if (count > maxWidth) maxWidth = count
            if (count > 0) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (maxY < minY) return 'P'
        val height = maxY - minY + 1
        if (height <= 13 && maxWidth <= 14) return 'P'

        val topBandEnd = (minY + (height * 0.16f).toInt()).coerceAtMost(maxY)
        var wTop = 0
        var topPeaks = 0
        for (y in minY..topBandEnd) {
            if (rowWidths[y] > wTop) wTop = rowWidths[y]
            if (rowSegments[y] > topPeaks) topPeaks = rowSegments[y]
        }

        val neckY = (minY + (height * 0.28f).toInt()).coerceIn(minY, maxY)
        val midY = (minY + (height * 0.52f).toInt()).coerceIn(minY, maxY)
        val baseY = (maxY - (height * 0.12f).toInt()).coerceIn(minY, maxY)

        val wNeck = rowWidths[neckY]
        val wMid = rowWidths[midY]
        val wBase = rowWidths[baseY]

        return when {
            wTop >= 10 && wNeck >= 8 && wTop >= (wBase * 0.65f).toInt() -> 'R'
            topPeaks >= 2 && wTop >= 9 && height >= 15 -> 'Q'
            height >= 17 && wTop <= 6 && wNeck >= 7 -> 'K'
            height >= 15 && wTop <= 6 && wMid >= 8 && wNeck <= 8 -> 'B'
            height >= 14 && (wNeck >= 8 || wMid >= 10) -> 'N'
            else -> 'P'
        }
    }

    /**
     * Deteksi papan otomatis berbasis proyeksi tepi luar + 49 perpotongan kisi kotak catur.
     * Menemukan posisi tepat tanpa perlu kalibrasi manual.
     */
    private fun autoDetectBoard(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 300 || h < 400) return null

        val minTop = (h * 0.10f).toInt().coerceAtLeast(4)
        val maxTop = (h * 0.58f).toInt()
        val maxMargin = (w * 0.14f).toInt()

        // 1. Saring baris Y yang menjadi batas atas papan (gelap di atas, hangat di bawah)
        val candidateTops = mutableListOf<Int>()
        for (y in minTop..maxTop) {
            var edgeHits = 0
            for (i in 0..7) {
                val x = ((w * (0.12f + i * 0.105f))).roundToInt().coerceIn(0, w - 1)
                if (isDarkUiPixel(bitmap.getPixel(x, y - 3)) &&
                    isWarmBoardPixel(bitmap.getPixel(x, y + 3))
                ) {
                    edgeHits++
                }
            }
            if (edgeHits >= 5) {
                candidateTops.add(y)
            }
        }

        if (candidateTops.isEmpty()) return null

        var bestScore = -1.0
        var bestLeft = 0
        var bestTop = 0
        var bestSize = w

        // 2. Evaluasi setiap kandidat (margin kiri/kanan simetris + top)
        for (top in candidateTops) {
            for (margin in 0..maxMargin step 2) {
                val size = w - 2 * margin
                if (size < (w * 0.72f).toInt() || top + size + 3 >= h) continue

                val score = scoreExactBoard(bitmap, margin.toFloat(), top.toFloat(), size.toFloat())
                if (score > bestScore) {
                    bestScore = score
                    bestLeft = margin
                    bestTop = top
                    bestSize = size
                }
            }
        }

        if (bestScore < 35.0) return null

        // 3. Penyempurnaan 1-piksel independen untuk left, top, dan size
        var fineScore = bestScore
        var fineLeft = bestLeft
        var fineTop = bestTop
        var fineSize = bestSize

        for (dTop in -4..4) {
            val t = bestTop + dTop
            if (t < 4) continue
            for (dLeft in -4..4) {
                val l = bestLeft + dLeft
                if (l < 0) continue
                for (dSize in -4..4) {
                    val s = bestSize + dSize
                    if (l + s > w || t + s + 4 >= h) continue
                    val sc = scoreExactBoard(bitmap, l.toFloat(), t.toFloat(), s.toFloat())
                    if (sc > fineScore) {
                        fineScore = sc
                        fineLeft = l
                        fineTop = t
                        fineSize = s
                    }
                }
            }
        }

        val l = fineLeft.toFloat()
        val t = fineTop.toFloat()
        val r = (fineLeft + fineSize).toFloat()
        val b = (fineTop + fineSize).toFloat()

        return listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b))
    }

    private fun scoreExactBoard(bitmap: Bitmap, left: Float, top: Float, size: Float): Double {
        val cell = size / 8f
        val d = (cell * 0.09f).coerceAtLeast(3f)

        // A. Cek ketajaman batas luar atas (top) & bawah (top + size)
        var borderHits = 0
        for (col in 0..7) {
            val x = (left + (col + 0.14f) * cell).roundToInt().coerceIn(0, bitmap.width - 1)
            val topOut = (top - 3f).roundToInt()
            val topIn = (top + 3f).roundToInt()
            val botIn = (top + size - 3f).roundToInt()
            val botOut = (top + size + 3f).roundToInt()

            if (topOut >= 0 && isDarkUiPixel(bitmap.getPixel(x, topOut)) &&
                isWarmBoardPixel(bitmap.getPixel(x, topIn))
            ) {
                borderHits++
            }
            if (botOut < bitmap.height && isWarmBoardPixel(bitmap.getPixel(x, botIn)) &&
                isDarkUiPixel(bitmap.getPixel(x, botOut))
            ) {
                borderHits++
            }
        }
        if (borderHits < 10) return -1.0

        // B. Cek arah kontras papan catur pada tengah papan
        var expectedSign = 0
        for (row in 3..4) {
            for (col in 3..4) {
                val ix = left + col * cell
                val iy = top + row * cell
                val diff = cornerContrast(bitmap, ix, iy, d)
                val parity = if ((row + col) % 2 == 0) 1 else -1
                if (abs(diff) < 18.0) return -1.0
                val s = if (diff > 0) parity else -parity
                if (expectedSign == 0) expectedSign = s
                else if (s != expectedSign) return -1.0
            }
        }
        if (expectedSign == 0) return -1.0

        // C. Evaluasi seluruh 49 titik pertemuan grid di dalam papan
        var gridScore = 0.0
        var validNodes = 0
        for (row in 1..7) {
            for (col in 1..7) {
                val ix = left + col * cell
                val iy = top + row * cell
                val diff = cornerContrast(bitmap, ix, iy, d)
                val parity = if ((row + col) % 2 == 0) 1 else -1
                val signedDiff = diff * parity * expectedSign

                if (signedDiff > 18.0) {
                    gridScore += minOf(signedDiff, 95.0) / 30.0
                    validNodes++
                } else if (signedDiff < -10.0) {
                    gridScore -= 2.5
                }
            }
        }

        if (validNodes < 36) return -1.0
        return gridScore + (borderHits * 2.5)
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

        if (abs(tl - br) > 35.0 || abs(tr - bl) > 35.0) return 0.0
        return ((tl + br) - (tr + bl)) * 0.5
    }

    private fun pixelLum(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
}
