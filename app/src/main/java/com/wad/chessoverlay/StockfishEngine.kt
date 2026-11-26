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

    @Synchronized
    fun start() {
        if (isReady && process?.isAlive == true) return

        try {
            stop()

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val stockfishFile = File(nativeLibDir, "libstockfish.so")

            if (!stockfishFile.exists()) {
                errorMsg = "libstockfish.so hilang"
                isReady = false
                return
            }

            process = ProcessBuilder(stockfishFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            sendCommand("uci")
            val uciOutput = waitFor("uciok")
            if (!uciOutput.contains("uciok")) {
                errorMsg = "Gagal UCI"
                isReady = false
                return
            }

            sendCommand("setoption name Threads value 2")
            sendCommand("setoption name Hash value 32")

            sendCommand("isready")
            val readyOutput = waitFor("readyok")
            if (readyOutput.contains("readyok")) {
                isReady = true
                errorMsg = ""
            } else {
                errorMsg = "Gagal readyok"
                isReady = false
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Error Start"
            isReady = false
        }
    }

    private fun sendCommand(cmd: String): Boolean {
        return try {
            val w = writer ?: return false
            w.write("$cmd\n")
            w.flush()
            true
        } catch (e: Exception) {
            isReady = false
            false
        }
    }

    private fun waitFor(token: String): String {
        val sb = StringBuilder()
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
                if (line!!.contains(token)) break
            }
        } catch (e: Exception) {
            isReady = false
        }
        return sb.toString()
    }

    @Synchronized
    fun getBestMove(fen: String): String {
        if (fen.isBlank()) return ""

        // Coba hitung langkah; jika proses sempat tertutup, nyalakan ulang otomatis 1x
        val firstTry = queryBestMoveInternal(fen)
        if (firstTry != null) return firstTry

        isReady = false
        start()
        if (!isReady) return ""

        return queryBestMoveInternal(fen) ?: ""
    }

    private fun queryBestMoveInternal(fen: String): String? {
        if (!isReady || process?.isAlive != true) return null

        if (!sendCommand("isready")) return null
        if (!waitFor("readyok").contains("readyok")) return null

        if (!sendCommand("position fen $fen")) return null
        if (!sendCommand("go movetime 800")) return null

        var bestMove = ""
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val text = line!!
                if (text.contains("CRITICAL ERROR")) {
                    errorMsg = "FEN ditolak"
                    isReady = false
                    return ""
                }
                if (text.startsWith("bestmove")) {
                    val parts = text.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        bestMove = parts[1]
                    }
                    break
                }
            }
        } catch (e: Exception) {
            isReady = false
            return null
        }

        if (process?.isAlive != true && bestMove.isEmpty()) {
            isReady = false
            return null
        }

        errorMsg = ""
        return if (bestMove.isEmpty() || bestMove == "(none)") "" else bestMove
    }

    @Synchronized
    fun stop() {
        try {
            if (process != null) {
                try {
                    writer?.write("quit\n")
                    writer?.flush()
                } catch (_: Exception) {
                }
                writer?.close()
                reader?.close()
                process?.destroy()
            }
        } catch (_: Exception) {
        } finally {
            writer = null
            reader = null
            process = null
            isReady = false
        }
    }
}
