package com.wad.chessoverlay

class ChessLogic {
    val board = Array(8) { CharArray(8) { '.' } }

    fun updateBoard(newBoard: Array<CharArray>) {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = newBoard[r][c]
            }
        }
    }

    fun printBoard(): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            for (c in 0..7) {
                sb.append(board[r][c]).append(" ")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    // Ngubah papan jadi FEN string buat Stockfish
    fun toFEN(turn: String = "w"): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var emptyCount = 0
            for (c in 0..7) {
                val piece = board[r][c]
                if (piece == '.') {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    // Karena kita belum deteksi jenis bidak (hanya warna), kita tebak aja jadi pion 'p' atau 'P'
                    // (Nanti kita upgrade biar bisa deteksi Kuda, Benteng, dll)
                    if (piece == 'w') sb.append('P') 
                    else if (piece == 'b') sb.append('p')
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < 7) sb.append("/")
        }
        // Tambahin info standar FEN
        return "$sb $turn KQkq - 0 1"
    }
}
