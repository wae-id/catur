package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray

class BoardReader(private val context: Context) {
    
    private val chessLogic = ChessLogic()

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

    fun scanFullBoard(screenBitmap: Bitmap): String {
        val points = loadCalibration() ?: return ""
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)
        
        val newBoard = Array(8) { CharArray(8) { '.' } }

        for (row in 0..7) {
            for (col in 0..7) {
                val center = Homography.transformPoint(matrix, PointF(col + 0.5f, row + 0.5f))
                try {
                    var totalR = 0; var totalG = 0; var totalB = 0; var count = 0
                    // Ambil sample lebih banyak (7x7 pixel) biar stabil
                    for (dx in -3..3) {
                        for (dy in -3..3) {
                            val px = center.x.toInt() + dx
                            val py = center.y.toInt() + dy
                            if (px in 0 until screenBitmap.width && py in 0 until screenBitmap.height) {
                                val c = screenBitmap.getPixel(px, py)
                                totalR += Color.red(c); totalG += Color.green(c); totalB += Color.blue(c)
                                count++
                            }
                        }
                    }
                    val r = totalR / count; val g = totalG / count; val b = totalB / count
                    
                    // LOGIKA BARU: Lebih ketat bedain Putih, Hitam, sama Papan (Coklat)
                    // Putih: RGB tinggi semua
                    if (r > 190 && g > 190 && b > 190) {
                        newBoard[row][col] = 'w'
                    } 
                    // Hitam: RGB rendah semua DAN warnanya cenderung netral (bukan coklat gelap)
                    else if (r < 110 && g < 110 && b < 110 && Math.abs(r - g) < 30 && Math.abs(r - b) < 30) {
                        newBoard[row][col] = 'b'
                    } 
                    // Kosong (Papan coklat: Red lebih tinggi dari Green/Blue)
                    else {
                        newBoard[row][col] = '.'
                    }
                } catch (e: Exception) { newBoard[row][col] = '.' }
            }
        }
        chessLogic.updateBoard(newBoard)
        return chessLogic.toFEN("w") // Anggap selalu giliran Putih buat ngetes
    }
}
