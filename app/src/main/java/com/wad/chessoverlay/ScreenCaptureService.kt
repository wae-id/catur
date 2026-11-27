package com.wad.chessoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
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
    private val isPerformingMove = AtomicBoolean(false)

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastProcessTime = 0L
    private var lastSolvedFen = ""
    private var lastExecutedFen = ""
    private var currentBestMove = ""
    private var currentEval = ""
    private var clearArrowStreak = 0
    private var isAutoPlayEnabled = true

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TOGGLE_AUTOPLAY = "ACTION_TOGGLE_AUTOPLAY"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "EXTRA_DATA_INTENT"
        const val EXTRA_SIDE_MODE = "EXTRA_SIDE_MODE"
        const val EXTRA_AUTO_PLAY = "EXTRA_AUTO_PLAY"
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

        if (intent?.action == ACTION_TOGGLE_AUTOPLAY) {
            isAutoPlayEnabled = !isAutoPlayEnabled
            lastExecutedFen = ""
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
            return START_STICKY
        }

        if (intent?.action == ACTION_START) {
            isAutoPlayEnabled = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
            startForegroundServiceCompat()

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA_INTENT)
            }

            val sideMode = intent.getStringExtra(EXTRA_SIDE_MODE) ?: "AUTO"
            when (sideMode) {
                "BLACK" -> {
                    boardReader.autoDetectSide = false
                    boardReader.isUserPlayingBlack = true
                }
                "WHITE" -> {
                    boardReader.autoDetectSide = false
                    boardReader.isUserPlayingBlack = false
                }
                else -> {
                    boardReader.autoDetectSide = true
                    boardReader.isUserPlayingBlack = false
                }
            }

            boardReader.resetGame()
            lastSolvedFen = ""
            lastExecutedFen = ""
            currentBestMove = ""
            currentEval = ""
            clearArrowStreak = 0

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
                        val colorName = if (boardReader.isUserPlayingBlack) "Hitam" else "Putih"
                        val sideLabel = if (boardReader.autoDetectSide) "Auto: $colorName" else colorName
                        val modeTag = when {
                            !isAutoPlayEnabled -> "Manual"
                            AutoTapAccessibilityService.instance == null -> "Aksesibilitas Off"
                            else -> "BOT"
                        }
                        val status = when {
                            boardReader.statusNote.isNotEmpty() -> boardReader.statusNote
                            engine.errorMsg.isNotEmpty() -> engine.errorMsg
                            isPerformingMove.get() -> "Melangkah..."
                            isEngineBusy.get() -> "Menghitung..."
                            currentBestMove.isNotEmpty() -> "Siap"
                            else -> "Memindai..."
                        }
                        overlayView?.fpsText = "FPS: $fps | $sideLabel [$modeTag] ($status)"
                        overlayView?.evalText = currentEval
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
                if (boardReader.statusNote == "Menunggu lawan..." ||
                    boardReader.statusNote == "Papan tertutup..."
                ) {
                    clearArrowStreak++
                    if (clearArrowStreak >= 3) {
                        lastSolvedFen = ""
                        lastExecutedFen = ""
                        currentBestMove = ""
                        currentEval = ""
                    }
                }
                Handler(mainLooper).post {
                    overlayView?.points = currentPoints
                    overlayView?.isFlipped = boardReader.isUserPlayingBlack
                    overlayView?.bestMove = currentBestMove
                    overlayView?.evalText = currentEval
                    overlayView?.invalidate()
                }
                return
            }

            clearArrowStreak = 0

            Handler(mainLooper).post {
                overlayView?.points = currentPoints
                overlayView?.isFlipped = boardReader.isUserPlayingBlack
                overlayView?.invalidate()
            }

            if (fen != lastSolvedFen && isEngineBusy.compareAndSet(false, true)) {
                val targetFen = fen
                val snapshotPoints = currentPoints
                val snapshotFlipped = boardReader.isUserPlayingBlack

                engineExecutor.execute {
                    try {
                        val (move, eval) = engine.getBestMoveWithEval(targetFen)
                        if (move.isNotEmpty()) {
                            lastSolvedFen = targetFen
                            currentBestMove = move
                            currentEval = eval
                            Handler(mainLooper).post {
                                overlayView?.bestMove = currentBestMove
                                overlayView?.evalText = currentEval
                                overlayView?.invalidate()
                            }

                            triggerAutoMoveIfReady(targetFen, move, snapshotPoints, snapshotFlipped)
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

    private fun triggerAutoMoveIfReady(
        fen: String,
        move: String,
        calibPoints: List<PointF>?,
        isFlipped: Boolean
    ) {
        if (!isAutoPlayEnabled) return
        val tapService = AutoTapAccessibilityService.instance ?: return
        if (calibPoints == null || calibPoints.size != 4 || move.length < 4) return
        if (fen == lastExecutedFen) return
        if (!isPerformingMove.compareAndSet(false, true)) return

        var fromCol = move[0] - 'a'
        var fromRow = 8 - Character.getNumericValue(move[1])
        var toCol = move[2] - 'a'
        var toRow = 8 - Character.getNumericValue(move[3])

        if (fromCol !in 0..7 || fromRow !in 0..7 || toCol !in 0..7 || toRow !in 0..7) {
            isPerformingMove.set(false)
            return
        }

        if (isFlipped) {
            fromCol = 7 - fromCol
            fromRow = 7 - fromRow
            toCol = 7 - toCol
            toRow = 7 - toRow
        }

        val src = listOf(PointF(0f, 0f), PointF(8f, 0f), PointF(8f, 8f), PointF(0f, 8f))
        val matrix = Homography.getPerspectiveTransform(src, calibPoints)

        val p1 = Homography.transformPoint(matrix, PointF(fromCol + 0.5f, fromRow + 0.5f))
        val p2 = Homography.transformPoint(matrix, PointF(toCol + 0.5f, toRow + 0.5f))

        var promoPt: PointF? = null
        if (move.length >= 5) {
            val promoRow = when (move[4].lowercaseChar()) {
                'q' -> 0
                'n' -> 1
                'r' -> 2
                'b' -> 3
                else -> 0
            }
            promoPt = Homography.transformPoint(matrix, PointF(toCol + 0.5f, promoRow + 0.5f))
        }

        // Jeda 450ms agar panah terlihat sejenak dan animasi lawan benar-benar selesai
        Handler(mainLooper).postDelayed({
            if (!isAutoPlayEnabled || boardReader.statusNote == "Papan tertutup...") {
                isPerformingMove.set(false)
                return@postDelayed
            }

            lastExecutedFen = fen
            tapService.performChessMove(
                startX = p1.x,
                startY = p1.y,
                endX = p2.x,
                endY = p2.y,
                promoX = promoPt?.x,
                promoY = promoPt?.y
            ) {
                isPerformingMove.set(false)
            }
        }, 450L)
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
        val toggleIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_TOGGLE_AUTOPLAY
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (isAutoPlayEnabled) "Jeda Auto-Bot" else "Lanjut Auto-Bot"
        val statusSubtext = if (isAutoPlayEnabled) "Auto-Bot Main Sendiri Aktif" else "Mode Manual (Hanya Panah)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Overlay Aktif")
            .setContentText(statusSubtext)
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(0, toggleLabel, togglePendingIntent)
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
