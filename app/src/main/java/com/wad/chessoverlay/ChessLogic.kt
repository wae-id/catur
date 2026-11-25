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
                    // Sementara kita anggap semua bidak adalah Pion (P/p)
                    if (piece == 'w') sb.append('P') 
                    else if (piece == 'b') sb.append('p')
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < 7) sb.append("/")
        }
        return "$sb $turn KQkq - 0 1"
    }
}
