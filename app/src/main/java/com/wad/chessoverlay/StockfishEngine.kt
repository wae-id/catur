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

    fun start() {
        try {
            val destFile = File(context.filesDir, "stockfish")
            if (!destFile.exists()) {
                val inputStream = context.assets.open("stockfish")
                val outputStream = destFile.outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                destFile.setExecutable(true)
            }

            process = ProcessBuilder(destFile.absolutePath).redirectErrorStream(true).start()
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            sendCommand("uci")
            waitFor("uciok")
            isReady = true
        } catch (e: Exception) {
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
        if (!isReady) return "Engine belum siap"
        
        sendCommand("position fen $fen")
        sendCommand("go depth 12")
        
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
        return bestMove
    }

    fun stop() {
        sendCommand("quit")
        process?.destroy()
        process = null
        isReady = false
    }
}
