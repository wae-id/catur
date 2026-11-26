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
    private lateinit var btnStop: Button
    private lateinit var statusText: TextView

    private val sideModes = arrayOf("AUTO", "WHITE", "BLACK")
    private var sideModeIndex = 0

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
            val selectedMode = sideModes[sideModeIndex]
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, result.data)
                putExtra(ScreenCaptureService.EXTRA_SIDE_MODE, selectedMode)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Status: Aktif (Auto-Scan Otomatis)"
        } else {
            statusText.text = "Status: Izin rekam layar dibatalkan"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pastikan tidak ada koordinat papan lama yang tersisa
        getSharedPreferences("ChessOverlay", Context.MODE_PRIVATE)
            .edit()
            .remove("calibration_points")
            .remove("auto_locked_points")
            .apply()

        requestNotificationPermissionIfNeeded()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 140, 50, 50)
        }

        statusText = TextView(this).apply {
            text = "Status: Siap (100% Otomatis Tanpa Atur Posisi)"
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
            text = "2. Warna Bidak: OTOMATIS (Putih/Hitam)"
            setOnClickListener {
                sideModeIndex = (sideModeIndex + 1) % sideModes.size
                text = when (sideModes[sideModeIndex]) {
                    "AUTO" -> "2. Warna Bidak: OTOMATIS (Putih/Hitam)"
                    "WHITE" -> "2. Warna Bidak: KUNCI PUTIH"
                    else -> "2. Warna Bidak: KUNCI HITAM"
                }
            }
        }

        btnStart = Button(this).apply {
            text = "3. Mulai Capture"
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
