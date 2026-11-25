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
            val nnueFile = File(context.filesDir, "nn-5af11540bbfe.nnue")
            if (!nnueFile.exists() || nnueFile.length() < 1000000L) {
                val nnueStream = context.assets.open("nn-5af11540bbfe.nnue")
                val outStream = nnueFile.outputStream()
                nnueStream.copyTo(outStream)
                nnueStream.close()
                outStream.close()
            }

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val stockfishFile = File(nativeLibDir, "libstockfish.so")

            process = ProcessBuilder(stockfishFile.absolutePath).redirectErrorStream(true).start()
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            sendCommand("setoption name EvalFile value ${nnueFile.absolutePath}")
            sendCommand("uci")
            
            val output = waitFor("uciok")
            if (output.contains("uciok")) {
                isReady = true
            } else {
                errorMsg = "Gagal UCI"
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
        if (!isReady) return ""
        
        sendCommand("position fen $fen")
        sendCommand("go movetime 1000")
        
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
            return ""
        }
        
        if (bestMove.isEmpty() || bestMove == "(none)") {
            return "e2e4" // Fallback darurat biar panah tetep muncul
        }
        return bestMove
    }

    fun stop() {
        sendCommand("quit")
        process?.destroy()
        process = null
        isReady = false
    }
}
