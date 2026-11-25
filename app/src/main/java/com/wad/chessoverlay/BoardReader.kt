package com.wad.chessoverlay

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray

class BoardReader(private val context: Context) {
    
    // Fungsi buat nge-load 4 titik yang udah disimpen
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

    // Ngecek apakah papan udah siap dibaca
    fun isBoardReady(): Boolean {
        return loadCalibration() != null
    }
}
