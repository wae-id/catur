package com.wad.chessoverlay

import android.graphics.PointF

object Homography {
    // Fungsi ajaib buat nyari matriks 3x3 buat nge-flatten 4 titik 3D jadi 2D
    fun getPerspectiveTransform(src: List<PointF>, dst: List<PointF>): FloatArray {
        val m = FloatArray(9)
        val a = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            a[i][0] = src[i].x.toDouble(); a[i][1] = src[i].y.toDouble(); a[i][2] = 1.0
            a[i][3] = 0.0; a[i][4] = 0.0; a[i][5] = 0.0
            a[i][6] = (-src[i].x * dst[i].x).toDouble(); a[i][7] = (-src[i].y * dst[i].x).toDouble()
            a[i][8] = dst[i].x.toDouble()

            a[i + 4][0] = 0.0; a[i + 4][1] = 0.0; a[i + 4][2] = 0.0
            a[i + 4][3] = src[i].x.toDouble(); a[i + 4][4] = src[i].y.toDouble(); a[i + 4][5] = 1.0
            a[i + 4][6] = (-src[i].x * dst[i].y).toDouble(); a[i + 4][7] = (-src[i].y * dst[i].y).toDouble()
            a[i + 4][8] = dst[i].y.toDouble()
        }
        
        for (i in 0 until 8) {
            var maxRow = i
            for (j in i + 1 until 8) if (Math.abs(a[j][i]) > Math.abs(a[maxRow][i])) maxRow = j
            val temp = a[i]; a[i] = a[maxRow]; a[maxRow] = temp
            for (j in i + 1 until 8) {
                val factor = a[j][i] / a[i][i]
                for (k in i until 9) a[j][k] -= factor * a[i][k]
            }
        }

        val h = DoubleArray(9)
        for (i in 7 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until 8) sum += a[i][j] * h[j]
            h[i] = (a[i][8] - sum) / a[i][i]
        }
        h[8] = 1.0

        for (i in 0..8) m[i] = h[i].toFloat()
        return m
    }

    // Fungsi buat ngetes posisi titik pake matriks di atas
    fun transformPoint(matrix: FloatArray, point: PointF): PointF {
        val x = point.x
        val y = point.y
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        return PointF(
            (matrix[0] * x + matrix[1] * y + matrix[2]) / w,
            (matrix[3] * x + matrix[4] * y + matrix[5]) / w
        )
    }
}
