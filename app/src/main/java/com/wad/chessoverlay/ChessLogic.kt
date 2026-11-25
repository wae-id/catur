package com.wad.chessoverlay

class ChessLogic {
    // Papan 8x8. Karakter: '.' = kosong, 'w' = bidak putih, 'b' = bidak hitam
    val board = Array(8) { CharArray(8) { '.' } }

    // Nge-update papan berdasarkan hasil scan
    fun updateBoard(newBoard: Array<CharArray>) {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = newBoard[r][c]
            }
        }
    }

    // Nampilin papan jadi teks biar bisa dibaca di overlay
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
}
