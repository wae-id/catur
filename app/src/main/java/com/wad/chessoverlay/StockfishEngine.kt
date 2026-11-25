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
                errorMsg = "Binary libstockfish.so tidak ditemukan"
                isReady = false
                return
            }

            process = ProcessBuilder(stockfishFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // 1. Wajib kirim "uci" lebih dulu dan tunggu "uciok"
            sendCommand("uci")
            val uciOutput = waitFor("uciok")
            if (!uciOutput.contains("uciok")) {
                errorMsg = "Gagal inisialisasi UCI"
                isReady = false
                return
            }

            // 2. Atur opsi setelah uciok (NNUE sudah tertanam di dalam binary Stockfish terbaru)
            sendCommand("setoption name Threads value 2")
            sendCommand("setoption name Hash value 32")

            // 3. Pastikan engine siap sebelum menerima posisi FEN
            sendCommand("isready")
            val readyOutput = waitFor("readyok")
            if (readyOutput.contains("readyok")) {
                isReady = true
                errorMsg = ""
            } else {
                errorMsg = "Engine tidak merespons readyok"
                isReady = false
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Error Start Stockfish"
            isReady = false
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            writer?.write("$cmd\n")
            writer?.flush()
        } catch (e: Exception) {
            errorMsg = e.message ?: "Error kirim perintah"
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
            errorMsg = e.message ?: "Error baca output"
        }
        return sb.toString()
    }

    @Synchronized
    fun getBestMove(fen: String): String {
        if (!isReady || process?.isAlive != true) {
            start()
            if (!isReady) return ""
        }

        if (fen.isBlank()) return ""

        sendCommand("isready")
        if (!waitFor("readyok").contains("readyok")) return ""

        sendCommand("position fen $fen")
        sendCommand("go movetime 1000")

        var bestMove = ""
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                if (line!!.startsWith("bestmove")) {
                    val parts = line!!.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        bestMove = parts[1]
                    }
                    break
                }
            }
        } catch (e: Exception) {
            return ""
        }

        if (bestMove.isEmpty() || bestMove == "(none)") {
            return ""
        }
        return bestMove
    }

    @Synchronized
    fun stop() {
        try {
            if (process != null) {
                sendCommand("quit")
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
