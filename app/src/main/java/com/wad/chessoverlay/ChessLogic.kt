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

        // BATASI PION MAKSIMAL 8 BIAR FEN LEGAL
        var wp = 0
        var bp = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (workBoard[r][c] == 'w' && wp < 8) wp++
                else if (workBoard[r][c] == 'w') workBoard[r][c] = '.'
                
                if (workBoard[r][c] == 'b' && bp < 8) bp++
                else if (workBoard[r][c] == 'b') workBoard[r][c] = '.'
            }
        }

        // PAKSA RAJA ADA BIAR STOCKFISH MAU MIKIR
        workBoard[7][4] = 'K' 
        workBoard[0][4] = 'k'

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
