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

    fun toFEN(turn: String = "w", isFlipped: Boolean = false): String {
        // Kalau main sebagai hitam (papan kebalik), kita balik array-nya
        val workBoard = Array(8) { CharArray(8) { '.' } }
        for (r in 0..7) {
            for (c in 0..7) {
                if (isFlipped) {
                    workBoard[r][c] = board[7 - r][7 - c]
                } else {
                    workBoard[r][c] = board[r][c]
                }
            }
        }

        // Paksa ada Raja biar FEN legal
        if (!workBoard.any { it.contains('K') }) workBoard[7][4] = 'K' // Putih di e1
        if (!workBoard.any { it.contains('k') }) workBoard[0][4] = 'k' // Hitam di e8

        val sb = StringBuilder()
        for (r in 0..7) {
            var emptyCount = 0
            for (c in 0..7) {
                val piece = workBoard[r][c]
                if (piece == '.') {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    if (piece == 'w') sb.append('P') 
                    else if (piece == 'b') sb.append('p')
                    else if (piece == 'K') sb.append('K')
                    else if (piece == 'k') sb.append('k')
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < 7) sb.append("/")
        }
        return "$sb $turn - - 0 1"
    }
}
