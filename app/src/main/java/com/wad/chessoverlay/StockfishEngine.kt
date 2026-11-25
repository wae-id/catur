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
            // 1. Copy NNUE file dari assets ke internal storage
            val nnueFile = File(context.filesDir, "nn-5af11540bbfe.nnue")
            if (!nnueFile.exists()) {
                try {
                    val nnueStream = context.assets.open("nn-5af11540bbfe.nnue")
                    val outStream = nnueFile.outputStream()
                    nnueStream.copyTo(outStream)
                    nnueStream.close()
                    outStream.close()
                } catch (e: Exception) {
                    errorMsg = "Gagal copy NNUE"
                    return
                }
            }

            // 2. Jalanin binary stockfish
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val stockfishFile = File(nativeLibDir, "libstockfish.so")

            if (!stockfishFile.exists()) {
                errorMsg = "File libstockfish.so gak ada"
                return
            }

            process = ProcessBuilder(stockfishFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // 3. Kasih tau Stockfish lokasi NNUE-nya
            sendCommand("setoption name EvalFile value ${nnueFile.absolutePath}")
            sendCommand("uci")
            
            val output = waitFor("uciok")
            if (output.contains("uciok")) {
                isReady = true
                errorMsg = "" // Bersihin error
            } else {
                // Kalo gagal, simpen output aslinya biar kita tau kenapa
                errorMsg = "Output: ${output.take(50)}" 
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
