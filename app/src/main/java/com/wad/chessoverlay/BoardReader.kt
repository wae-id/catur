package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray

class BoardReader(private val context: Context) {
    
    private val chessLogic = ChessLogic()

    // Ngeload 4 titik kalibrasi dari memori HP
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

    // Ngecek apakah udah pernah kalibrasi
    fun isBoardReady(): Boolean = loadCalibration() != null

    // Nge-scan 64 kotak dari gambar layar
    fun scanFullBoard(screenBitmap: Bitmap): String {
        val points = loadCalibration() ?: return "Kalibrasi Belum Ada!"
        
        // Papan virtual 8x8
        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, points)
        
        val newBoard = Array(8) { CharArray(8) { '.' } }

        for (row in 0..7) {
            for (col in 0..7) {
                // Titik tengah tiap kotak (0.5, 1.5, dst)
                val center = Homography.transformPoint(matrix, PointF(col + 0.5f, row + 0.5f))
                
                try {
                    // Ambil warna rata-rata dari area 5x5 pixel biar anti-noise
                    var totalR = 0
                    var totalG = 0
                    var totalB = 0
                    var count = 0
                    
                    for (dx in -2..2) {
                        for (dy in -2..2) {
                            val px = center.x.toInt() + dx
                            val py = center.y.toInt() + dy
                            // Pastiin pixel engga keluar batas layar
                            if (px in 0 until screenBitmap.width && py in 0 until screenBitmap.height) {
                                val c = screenBitmap.getPixel(px, py)
                                totalR += Color.red(c)
                                totalG += Color.green(c)
                                totalB += Color.blue(c)
                                count++
                            }
                        }
                    }
                    
                    // Hitung rata-rata RGB
                    val r = totalR / count
                    val g = totalG / count
                    val b = totalB / count
                    
                    // Heuristik deteksi warna (Putih, Hitam, Kosong)
                    if (r > 180 && g > 180 && b > 180) {
                        newBoard[row][col] = 'w' // Pion/bidak Putih (terang)
                    } else if (r < 95 && g < 95 && b < 95) {
                        newBoard[row][col] = 'b' // Pion/bidak Hitam (gelap)
                    } else {
                        newBoard[row][col] = '.' // Kosong (warna papan coklat)
                    }
                    
                } catch (e: Exception) {
                    newBoard[row][col] = '?'
                }
            }
        }
        
        // Simpan hasil scan ke ChessLogic
        chessLogic.updateBoard(newBoard)
        return chessLogic.printBoard()
    }

    // Fungsi buat minta format FEN ke ChessLogic (dipake sama Stockfish)
    fun getFen(): String {
        return chessLogic.toFEN("w") // Anggap giliran putih dulu buat testing
    }
}
