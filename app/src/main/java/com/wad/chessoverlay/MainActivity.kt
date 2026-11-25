package com.wad.chessoverlay

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "Chess Overlay Ready\n\n(yuki lagi nyiapin otak stockfish-nya wad ><)"
            textSize = 20f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(50, 300, 50, 50)
        }
        
        setContentView(textView)
    }
}
