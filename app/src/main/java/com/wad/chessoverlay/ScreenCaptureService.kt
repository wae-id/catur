package com.wad.chessoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayCanvasView? = null
    private lateinit var boardReader: BoardReader

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastProcessTime = 0L

    companion object {
        const val ACTION_START = "ACTION_START"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "EXTRA_DATA_INTENT"
        private const val CHANNEL_ID = "ChessOverlayChannel"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        boardReader = BoardReader(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startForeground(NOTIF_ID, buildNotification())

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
            
            if (dataIntent != null) {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
                
                showOverlay()
                startCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread?.start()
        backgroundHandler = handlerThread?.let { Handler(it.looper) }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                frameCount++
                val currentTime = System.currentTimeMillis()
                
                // Update FPS aja
                if (currentTime - lastFpsTime >= 1000) {
                    val fps = frameCount
                    frameCount = 0
                    lastFpsTime = currentTime
                    
                    Handler(mainLooper).post {
                        overlayView?.fpsText = "FPS: $fps | Kalibrasi OK"
                        overlayView?.invalidate()
                    }
                }

                // Proses baca papan
                if (currentTime - lastProcessTime >= 500) {
                    lastProcessTime = currentTime
                    processBoardImage(image, width, height)
                }
                
                image.close()
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )
    }

    private fun processBoardImage(image: android.media.Image, width: Int, height: Int) {
        if (!boardReader.isBoardReady()) return
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            
            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val boardString = boardReader.scanFullBoard(bitmap)
            val currentPoints = boardReader.loadCalibration()
            
            Handler(mainLooper).post {
                overlayView?.boardText = "Papan:\n$boardString"
                overlayView?.points = currentPoints // Update titik buat gambar lingkaran merah
                overlayView?.invalidate()
            }
            
            bitmap.recycle()
        } catch (e: Exception) {
            // diamkan aja kalau error
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        // Pakai MATCH_PARENT biar bisa gambar titik di seluruh layar
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Biar nutupin status bar juga
            PixelFormat.TRANSLUCENT
        )

        overlayView = OverlayCanvasView(this).apply {
            points = boardReader.loadCalibration()
        }

        windowManager.addView(overlayView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Overlay Aktif")
            .setContentText("Memindai papan catur...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        backgroundHandler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
    }
}
