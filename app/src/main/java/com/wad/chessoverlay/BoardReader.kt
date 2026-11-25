package com.wad.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.json.JSONArray

class BoardReader(private val context: Context) {
    
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

    // Fungsi buat nge-sample warna 64 kotak papan dari gambar layar
    fun sampleBoardColors(screenBitmap: Bitmap): String? {
        val points = loadCalibration() ?: return null
        
        // Titik sudut papan virtual (0,0 sampai 8,8)
        val src = listOf(
            PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f)
        )
        
        // Matrix buat ngubah dari papan virtual ke koordinat layar HP
        val matrix = Homography.getPerspectiveTransform(src, points)
        
        // Kita cek 2 kotak aja buat testing: A1 (col 0, row 0) dan H8 (col 7, row 7)
        // Titik tengah kotak A1 adalah (0.5, 0.5), H8 adalah (7.5, 7.5)
        val a1Center = Homography.transformPoint(matrix, PointF(0.5f, 0.5f))
        val h8Center = Homography.transformPoint(matrix, PointF(7.5f, 7.5f))
        
        return try {
            // Baca warna pixel dari Bitmap layar
            val a1Color = screenBitmap.getPixel(a1Center.x.toInt(), a1Center.y.toInt())
            val h8Color = screenBitmap.getPixel(h8Center.x.toInt(), h8Center.y.toInt())
            
            val a1Hex = String.format("#%06X", 0xFFFFFF and a1Color)
            val h8Hex = String.format("#%06X", 0xFFFFFF and h8Color)
            
            "A1: $a1Hex | H8: $h8Hex"
        } catch (e: Exception) {
            "Error baca pixel"
        }
    }
}
