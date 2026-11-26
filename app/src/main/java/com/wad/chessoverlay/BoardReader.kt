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
    var autoDetectSide = true
    var isUserPlayingBlack = false
    var detectedPoints: List<PointF>? = null
    var statusNote = ""
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

        // Cek apakah papan tertutup popup/dialog (misal "Putih Menang" / menu)
        val l = points[0].x
        val t = points[0].y
        val boardSize = points[1].x - points[0].x
        if (boardSize > 100f && !isBoardUnobstructed(screenBitmap, l, t, boardSize)) {
            missedBoardFrames++
            if (missedBoardFrames >= 16 && loadManualCalibration() == null) {
                val newPoints = autoDetectBoard(screenBitmap)
                if (newPoints != null) detectedPoints = newPoints
                missedBoardFrames = 0
            }
            statusNote = "Papan tertutup..."
            return ""
        }
        missedBoardFrames = 0

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

        // Deteksi otomatis warna bidak pengguna (Putih vs Hitam)
        if (autoDetectSide) {
            val detectedBlackSide = detectSideAutomatically(detectedBoard)
            if (detectedBlackSide != null && detectedBlackSide != isUserPlayingBlack) {
                isUserPlayingBlack = detectedBlackSide
                chessLogic.reset()
            }
        }

        val detectedTurnFromHighlight = inferTurnFromHighlights(detectedBoard, highlightedSquares)

        // Konversi koordinat sorotan kuning ke orientasi papan catur standar
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
        val activeTurn = detectedTurnFromHighlight ?: chessLogic.currentTurn

        if (activeTurn != myTurn) {
            statusNote = "Menunggu lawan..."
            return ""
        }

        statusNote = ""
        return chessLogic.toFEN(myTurn)
    }

    /**
     * Memastikan 49 titik pertemuan kotak di dalam papan masih memiliki pola terang-gelap catur
     * dan tidak tertutup jendela dialog/popup.
     */
    private fun isBoardUnobstructed(bitmap: Bitmap, left: Float, top: Float, size: Float): Boolean {
        val cell = size / 8f
        val d = (cell * 0.14f).coerceAtLeast(3f)
        var validNodes = 0

        for (row in 1..7) {
            for (col in 1..7) {
                val ix = left + col * cell
                val iy = top + row * cell
                val diff = abs(cornerContrast(bitmap, ix, iy, d))
                if (diff > 14.0) {
                    validNodes++
                }
            }
        }
        return validNodes >= 28
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
        val cornerOffsets = arrayOf(
            0.09f to 0.09f,
            0.91f to 0.09f,
            0.09f to 0.91f,
            0.91f to 0.91f
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

        var isHighlighted = false
        if (bgCount > 0) {
            bgR /= bgCount
            bgG /= bgCount
            bgB /= bgCount
            val yellowScore = ((bgR + bgG) * 0.5) - bgB
            isHighlighted = yellowScore > 88.0 && bgR > 165.0 && bgG > 155.0
        }

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

        val isWhitePiece = (neutralWhiteCount >= 26) && (darkOutlineCount >= 8 || neutralWhiteCount >= 42)
        val isBlackPiece = !isWhitePiece && (veryDarkCount >= 44)

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

        return listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b))
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

        if (abs(tl - br) > 35.0 || abs(tr - bl) > 35.0) return 0.0
        return ((tl + br) - (tr + bl)) * 0.5
    }

    private fun pixelLum(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
}
