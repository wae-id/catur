package com.wad.chessoverlay

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class StockfishEngine(private val context: Context) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var isReady = false
    var errorMsg = ""

    fun start() {
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val srcFile = File(nativeLibDir, "libstockfish.so")
            
            // Kita copy ke folder internal aplikasi biar bisa dieksekusi
            val destFile = File(context.filesDir, "stockfish_engine")
            
            if (!destFile.exists() || destFile.length() < 10000L) {
                if (srcFile.exists()) {
                    srcFile.copyTo(destFile, overwrite = true)
                } else {
                    errorMsg = "File libstockfish.so tidak ditemukan"
                    return
                }
            }
            
            // Set izin eksekusi
            destFile.setExecutable(true)

            process = ProcessBuilder(destFile.absolutePath).redirectErrorStream(true).start()
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            sendCommand("uci")
            val output = waitFor("uciok")
            if (output.contains("uciok")) {
                isReady = true
            } else {
                errorMsg = "Gagal UCI init"
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Error Start"
            isReady = false
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            writer?.write("$cmd\n")
            writer?.flush()
        } catch (e: Exception) {}
    }

    private fun waitFor(token: String): String {
        val sb = StringBuilder()
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
                if (line!!.contains(token)) break
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    fun getBestMove(fen: String): String {
        if (!isReady) return "Gagal: $errorMsg"
        
        sendCommand("position fen $fen")
        sendCommand("go depth 10")
        
        var bestMove = ""
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                if (line!!.startsWith("bestmove")) {
                    bestMove = line!!.split(" ")[1]
                    break
                }
            }
        } catch (e: Exception) {
            return "Error baca langkah"
        }
        return if (bestMove.isNotEmpty()) bestMove else "Tidak ada langkah"
    }

    fun stop() {
        sendCommand("quit")
        process?.destroy()
        process = null
        isReady = false
    }
}
