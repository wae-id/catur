package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

class BoardReader(private val context: Context) {

    private val chessLogic = ChessLogic()
    var autoDetectSide = true
    var isUserPlayingBlack = false
    var detectedPoints: List<PointF>? = null
    var statusNote = ""

    init {
        detectedPoints = loadSavedPoints("calibration_points") ?: loadSavedPoints("auto_locked_points")
    }

    private fun loadSavedPoints(key: String): List<PointF>? {
        val prefs = context.getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val jsonString = prefs.getString(key, null) ?: return null

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

    private fun saveAutoLockedPoints(points: List<PointF>) {
        val prefs = context.getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("x", p.x.toDouble())
            obj.put("y", p.y.toDouble())
            jsonArray.put(obj)
        }
        prefs.edit().putString("auto_locked_points", jsonArray.toString()).apply()
    }

    fun isBoardReady(): Boolean = true

    fun resetGame() {
        chessLogic.reset()
        detectedPoints = loadSavedPoints("calibration_points") ?: loadSavedPoints("auto_locked_points")
        statusNote = ""
    }

    fun scanFullBoard(screenBitmap: Bitmap): String {
        if (detectedPoints == null) {
            val found = autoDetectBoard(screenBitmap)
            if (found != null) {
                detectedPoints = found
                saveAutoLockedPoints(found)
            }
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

        statusNote = ""
        return chessLogic.toFEN(myTurn)
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

    private fun autoDetectBoard(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 300 || h < 400) return null

        val minSize = (w * 0.85f).toInt()
        val maxSize = w
        val maxLeft = (w * 0.08f).toInt()
        val minTop = (h * 0.12f).toInt()
        val maxTop = (h * 0.55f).toInt()

        var bestScore = 0.0
        var bestLeft = 0
        var bestTop = 0
        var bestSize = 0

        for (size in minSize..maxSize step 6) {
            val leftLimit = minOf(maxLeft, w - size)
            for (left in 0..leftLimit step 4) {
                val topLimit = minOf(maxTop, h - size)
                for (top in minTop..topLimit step 6) {
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

        for (size in (bestSize - 8)..(bestSize + 8) step 1) {
            if (size < minSize || size > w) continue
            for (left in (bestLeft - 6)..(bestLeft + 6) step 1) {
                if (left < 0 || left + size > w) continue
                for (top in (bestTop - 6)..(bestTop + 6) step 1) {
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

        return snapBoardEdges(bitmap, fineLeft, fineTop, fineSize)
    }

    /**
     * Mengunci garis tepi atas & bawah papan tepat pada perbatasan latar gelap UI
     * dengan kotak papan catur agar ke-64 titik berada tepat di tengah petak.
     */
    private fun snapBoardEdges(bitmap: Bitmap, left: Int, top: Int, size: Int): List<PointF> {
        val sampleX = (left + size * 0.06f).roundToInt().coerceIn(0, bitmap.width - 1)
        var snappedTop = top
        for (y in (top - 25).coerceAtLeast(1)..(top + 25).coerceAtMost(bitmap.height - 2)) {
            val aboveLum = pixelLum(bitmap.getPixel(sampleX, y - 1))
            val insideLum = pixelLum(bitmap.getPixel(sampleX, y + 1))
            if (aboveLum < 70.0 && insideLum > 110.0) {
                snappedTop = y
                break
            }
        }

        val rawBottom = snappedTop + size
        var snappedBottom = rawBottom
        for (y in (rawBottom - 25).coerceAtLeast(1)..(rawBottom + 25).coerceAtMost(bitmap.height - 2)) {
            val insideLum = pixelLum(bitmap.getPixel(sampleX, y - 1))
            val belowLum = pixelLum(bitmap.getPixel(sampleX, y + 1))
            if (insideLum > 110.0 && belowLum < 70.0) {
                snappedBottom = y
                break
            }
        }

        val finalHeight = snappedBottom - snappedTop
        val finalWidth = if (abs(finalHeight - size) <= 18) finalHeight else size
        val snappedLeft = ((bitmap.width - finalWidth) / 2f).coerceAtLeast(0f)
        val snappedRight = (snappedLeft + finalWidth).coerceAtMost(bitmap.width.toFloat())

        return listOf(
            PointF(snappedLeft, snappedTop.toFloat()),
            PointF(snappedRight, snappedTop.toFloat()),
            PointF(snappedRight, snappedBottom.toFloat()),
            PointF(snappedLeft, snappedBottom.toFloat())
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
        val d = (cell * 0.13f).coerceAtLeast(3f)

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
