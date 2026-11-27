package com.wad.chessoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        var instance: AutoTapAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak perlu membaca event UI; koordinat diambil langsung dari deteksi papan
    }

    override fun onInterrupt() {
        // Dipanggil jika layanan dihentikan sistem
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    /**
     * Mengetuk petak asal (startX, startY), lalu petak tujuan (endX, endY),
     * serta memilih bidak promosi jika langkah adalah promosi pion.
     */
    fun performChessMove(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        promoX: Float? = null,
        promoY: Float? = null,
        onComplete: () -> Unit
    ) {
        tapAt(startX, startY) {
            mainHandler.postDelayed({
                tapAt(endX, endY) {
                    if (promoX != null && promoY != null) {
                        mainHandler.postDelayed({
                            tapAt(promoX, promoY) {
                                onComplete()
                            }
                        }, 260L)
                    } else {
                        onComplete()
                    }
                }
            }, 180L)
        }
    }

    private fun tapAt(x: Float, y: Float, onDone: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone()
                }
            },
            mainHandler
        )

        if (!dispatched) {
            onDone()
        }
    }
}
