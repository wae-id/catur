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
        val points = loadCalibration() ?: return "Kalibrasi Belum Ada!"
        
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)
        
        val newBoard = Array(8) { CharArray(8) { '.' } }

        for (row in 0..7) {
            for (col in 0..7) {
                // Titik tengah tiap kotak
                val center = Homography.transformPoint(matrix, PointF(col + 0.5f, row + 0.5f))
                
                try {
                    val pixelColor = screenBitmap.getPixel(center.x.toInt(), center.y.toInt())
                    
                    // Hitung kecerahan (0-255)
                    val r = Color.red(pixelColor)
                    val g = Color.green(pixelColor)
                    val b = Color.blue(pixelColor)
                    val brightness = (r * 0.299 + g * 0.587 + b * 0.114)
                    
                    // Heuristik sederhana: > 120 = cerah (putih), < 90 = gelap (hitam)
                    // Kalau di antara itu, anggap kosong atau bayangan
                    if (brightness > 130) {
                        newBoard[row][col] = 'w'
                    } else if (brightness < 80) {
                        newBoard[row][col] = 'b'
                    } else {
                        newBoard[row][col] = '.'
                    }
                    
                } catch (e: Exception) {
                    newBoard[row][col] = '?'
                }
            }
        }
        
        chessLogic.updateBoard(newBoard)
        return chessLogic.printBoard()
    }
}
