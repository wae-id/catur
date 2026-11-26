package com.wad.chessoverlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnSide: Button
    private lateinit var btnStart: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnResetAuto: Button
    private lateinit var btnStop: Button
    private lateinit var statusText: TextView

    private var isBlack = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            statusText.text = "Peringatan: Izin notifikasi ditolak"
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, result.data)
                putExtra("IS_BLACK", isBlack)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Status: Auto-Scan Aktif (${if (isBlack) "Hitam" else "Putih"})"
        } else {
            statusText.text = "Status: Izin rekam layar dibatalkan"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 140, 50, 50)
        }

        statusText = TextView(this).apply {
            text = "Status: Siap (Mode Auto-Scan Papan)"
            textSize = 18f
            setPadding(0, 0, 0, 40)
        }

        btnOverlay = Button(this).apply {
            text = "1. Izinkan Overlay"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    statusText.text = "Status: Overlay Sudah Diizinkan"
                }
            }
        }

        btnSide = Button(this).apply {
            text = "2. Main Sebagai: PUTIH"
            setOnClickListener {
                isBlack = !isBlack
                text = if (isBlack) "2. Main Sebagai: HITAM" else "2. Main Sebagai: PUTIH"
            }
        }

        btnStart = Button(this).apply {
            text = "3. Mulai Capture (Auto-Scan)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusText.text = "Error: Izinkan Overlay (No. 1) dulu!"
                    return@setOnClickListener
                }
                val projectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        btnCalibrate = Button(this).apply {
            text = "Kalibrasi Manual (Opsional)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
            }
        }

        btnResetAuto = Button(this).apply {
            text = "Reset ke Auto-Scan Otomatis"
            setOnClickListener {
                getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
                    .edit()
                    .remove("calibration_points")
                    .apply()
                statusText.text = "Status: Mode Auto-Scan Otomatis Diaktifkan"
            }
        }

        btnStop = Button(this).apply {
            text = "Hentikan Capture"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                statusText.text = "Status: Dihentikan"
            }
        }

        layout.addView(statusText)
        layout.addView(btnOverlay)
        layout.addView(btnSide)
        layout.addView(btnStart)
        layout.addView(btnCalibrate)
        layout.addView(btnResetAuto)
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
