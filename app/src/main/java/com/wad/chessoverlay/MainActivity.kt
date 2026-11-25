package com.wad.chessoverlay

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
    private lateinit var btnStart: Button
    private lateinit var statusText: TextView

    // Launcher buat minta izin rekam layar (MediaProjection)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Status: Capture Service Running"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 200, 50, 50)
        }

        statusText = TextView(this).apply {
            text = "Status: Idle"
            textSize = 18f
            setPadding(0, 0, 0, 50)
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
                    statusText.text = "Status: Overlay Diizinkan"
                }
            }
        }

        btnStart = Button(this).apply {
            text = "2. Mulai Capture Layar"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusText.text = "Error: Izinkan Overlay Dulu!"
                    return@setOnClickListener
                }
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        layout.addView(statusText)
        layout.addView(btnOverlay)
        layout.addView(btnStart)
        setContentView(layout)
    }
}
