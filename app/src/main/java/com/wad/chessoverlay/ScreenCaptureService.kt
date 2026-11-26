package com.wad.chessoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayCanvasView? = null
    private lateinit var boardReader: BoardReader
    private lateinit var engine: StockfishEngine

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val engineExecutor = Executors.newSingleThreadExecutor()
    private val isEngineBusy = AtomicBoolean(false)

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastProcessTime = 0L
    private var lastSolvedFen = ""
    private var currentBestMove = ""

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "EXTRA_DATA_INTENT"
        private const val CHANNEL_ID = "ChessOverlayChannel"
        private const val NOTIF_ID = 1
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        boardReader = BoardReader(this)
        engine = StockfishEngine(this)
        engineExecutor.execute { engine.start() }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            startForegroundServiceCompat()

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA_INTENT)
            }

            val isBlack = intent.getBooleanExtra("IS_BLACK", false)
            boardReader.isUserPlayingBlack = isBlack
            boardReader.resetGame()
            lastSolvedFen = ""
            currentBestMove = ""

            if (dataIntent != null && resultCode == Activity.RESULT_OK) {
                val projectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)

                showOverlay()
                startCapture()
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        mediaProjection?.registerCallback(projectionCallback, backgroundHandler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frameCount++
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastFpsTime >= 1000) {
                    val fps = frameCount
                    frameCount = 0
                    lastFpsTime = currentTime

                    Handler(mainLooper).post {
                        val side = if (boardReader.isUserPlayingBlack) "Hitam" else "Putih"
                        val status = when {
                            boardReader.statusNote.isNotEmpty() -> boardReader.statusNote
                            engine.errorMsg.isNotEmpty() -> engine.errorMsg
                            isEngineBusy.get() -> "Menghitung..."
                            else -> "Siap"
                        }
                        overlayView?.fpsText = "FPS: $fps | $side ($status)"
                        overlayView?.invalidate()
                    }
                }

                if (currentTime - lastProcessTime >= 220) {
                    lastProcessTime = currentTime
                    processBoardImage(image, width, height)
                }
            } finally {
                image.close()
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )
    }

    private fun processBoardImage(image: Image, width: Int, height: Int) {
        val bitmap = imageToBitmap(image, width, height) ?: return
        try {
            val fen = boardReader.scanFullBoard(bitmap)
            val currentPoints = boardReader.detectedPoints

            if (fen.isEmpty()) {
                // Jika sedang giliran lawan atau papan belum stabil, sembunyikan panah lama
                if (boardReader.statusNote == "Menunggu lawan...") {
                    lastSolvedFen = ""
                    currentBestMove = ""
                }
                Handler(mainLooper).post {
                    overlayView?.points = currentPoints
                    overlayView?.isFlipped = boardReader.isUserPlayingBlack
                    overlayView?.bestMove = currentBestMove
                    overlayView?.invalidate()
                }
                return
            }

            Handler(mainLooper).post {
                overlayView?.points = currentPoints
                overlayView?.isFlipped = boardReader.isUserPlayingBlack
                overlayView?.invalidate()
            }

            if (fen != lastSolvedFen && isEngineBusy.compareAndSet(false, true)) {
                engineExecutor.execute {
                    try {
                        val move = engine.getBestMove(fen)
                        if (move.isNotEmpty()) {
                            lastSolvedFen = fen
                            currentBestMove = move
                            Handler(mainLooper).post {
                                overlayView?.bestMove = currentBestMove
                                overlayView?.invalidate()
                            }
                        }
                    } finally {
                        isEngineBusy.set(false)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val paddedWidth = width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == width) {
                paddedBitmap
            } else {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                paddedBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        overlayView = OverlayCanvasView(this).apply {
            points = boardReader.detectedPoints
            isFlipped = boardReader.isUserPlayingBlack
        }

        windowManager.addView(overlayView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Overlay Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Overlay Aktif")
            .setContentText("Auto-Scan papan catur aktif")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()

        engineExecutor.execute { engine.stop() }
        engineExecutor.shutdown()

        backgroundHandler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
    }
}
