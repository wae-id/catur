package com.wad.chessoverlay

class ChessLogic {
    val board = Array(8) { CharArray(8) { '.' } }
    private var hasTrackedGame = false
    private var lastCandidateBoard: Array<CharArray>? = null

    fun reset() {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = '.'
            }
        }
        hasTrackedGame = false
        lastCandidateBoard = null
    }

    fun updateFromScan(screenBoard: Array<CharArray>, isFlipped: Boolean) {
        val oriented = Array(8) { CharArray(8) { '.' } }
        for (r in 0..7) {
            for (c in 0..7) {
                oriented[r][c] = if (isFlipped) {
                    screenBoard[7 - r][7 - c]
                } else {
                    screenBoard[r][c]
                }
            }
        }

        // Filter stabilitas: abaikan frame transisi saat bidak sedang beranimasi
        val prevCandidate = lastCandidateBoard
        lastCandidateBoard = copyBoard(oriented)
        if (prevCandidate != null && !sameOccupancy(prevCandidate, oriented)) {
            if (!isBoardEmpty()) return
        }

        // Deteksi posisi awal standar catur (baris 1-2 putih, baris 7-8 hitam)
        if (isStartingPosition(oriented)) {
            setStandardStartingBoard()
            hasTrackedGame = true
            return
        }

        if (hasTrackedGame) {
            applyIncrementalMove(oriented)
        } else {
            syncFromClassifiedBoard(oriented)
        }
    }

    private fun isStartingPosition(b: Array<CharArray>): Boolean {
        var topBlack = 0
        var bottomWhite = 0
        var middlePieces = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val p = b[r][c]
                if (r in 0..1 && p.isLowerCase()) topBlack++
                else if (r in 6..7 && p.isUpperCase()) bottomWhite++
                else if (r in 2..5 && p != '.') middlePieces++
            }
        }
        return topBlack >= 14 && bottomWhite >= 14 && middlePieces <= 1
    }

    private fun setStandardStartingBoard() {
        val blackBack = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')
        val whiteBack = charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')

        for (c in 0..7) {
            board[0][c] = blackBack[c]
            board[1][c] = 'p'
            for (r in 2..5) board[r][c] = '.'
            board[6][c] = 'P'
            board[7][c] = whiteBack[c]
        }
    }

    private fun applyIncrementalMove(newScan: Array<CharArray>) {
        val vacated = mutableListOf<Pair<Int, Int>>()
        val arrived = mutableListOf<Pair<Int, Int>>()

        for (r in 0..7) {
            for (c in 0..7) {
                val oldP = board[r][c]
                val newP = newScan[r][c]
                val oldColor = pieceColor(oldP)
                val newColor = pieceColor(newP)

                if (oldColor != newColor) {
                    if (newColor == 0) {
                        vacated.add(r to c)
                    } else {
                        arrived.add(r to c)
                    }
                }
            }
        }

        if (vacated.isEmpty() && arrived.isEmpty()) return

        // 1. Langkah normal atau makan bidak (1 kotak kosong, 1 kotak terisi)
        if (vacated.size == 1 && arrived.size == 1) {
            val (fr, fc) = vacated[0]
            val (tr, tc) = arrived[0]
            var movingPiece = board[fr][fc]

            if (movingPiece == 'P' && tr == 0) movingPiece = 'Q'
            if (movingPiece == 'p' && tr == 7) movingPiece = 'q'

            board[fr][fc] = '.'
            board[tr][tc] = movingPiece
            return
        }

        // 2. Rokade (2 kotak kosong, 2 kotak terisi di baris 0 atau 7)
        if (vacated.size == 2 && arrived.size == 2) {
            val r = vacated[0].first
            if ((r == 0 || r == 7) && vacated.all { it.first == r } && arrived.all { it.first == r }) {
                val kingCol = if (board[r][4] == 'K' || board[r][4] == 'k') 4 else -1
                if (kingCol == 4) {
                    val isWhite = (r == 7)
                    val kingChar = if (isWhite) 'K' else 'k'
                    val rookChar = if (isWhite) 'R' else 'r'

                    if (arrived.any { it.second == 6 } && arrived.any { it.second == 5 }) {
                        board[r][4] = '.'
                        board[r][7] = '.'
                        board[r][6] = kingChar
                        board[r][5] = rookChar
                        return
                    }
                    if (arrived.any { it.second == 2 } && arrived.any { it.second == 3 }) {
                        board[r][4] = '.'
                        board[r][0] = '.'
                        board[r][2] = kingChar
                        board[r][3] = rookChar
                        return
                    }
                }
            }
        }

        // 3. En Passant (2 kotak kosong, 1 kotak terisi)
        if (vacated.size == 2 && arrived.size == 1) {
            val (tr, tc) = arrived[0]
            val targetColor = pieceColor(newScan[tr][tc])
            val mover = vacated.firstOrNull { (vr, vc) -> pieceColor(board[vr][vc]) == targetColor }
            if (mover != null) {
                val movingPiece = board[mover.first][mover.second]
                for ((vr, vc) in vacated) board[vr][vc] = '.'
                board[tr][tc] = movingPiece
                return
            }
        }

        // 4. Jika terjadi perubahan besar (ganti papan / reset manual), sinkronisasi ulang
        if (vacated.size + arrived.size >= 6) {
            hasTrackedGame = false
            syncFromClassifiedBoard(newScan)
        }
    }

    private fun syncFromClassifiedBoard(classified: Array<CharArray>) {
        var whiteKingCount = 0
        var blackKingCount = 0

        for (r in 0..7) {
            for (c in 0..7) {
                var p = classified[r][c]
                // Pion tidak legal di baris 0 atau 7
                if (p == 'P' && (r == 0 || r == 7)) p = 'Q'
                if (p == 'p' && (r == 0 || r == 7)) p = 'q'

                if (p == 'K') {
                    whiteKingCount++
                    if (whiteKingCount > 1) p = 'Q'
                } else if (p == 'k') {
                    blackKingCount++
                    if (blackKingCount > 1) p = 'q'
                }
                board[r][c] = p
            }
        }

        // Pastikan tepat 1 Raja Putih dan 1 Raja Hitam ada agar FEN selalu sah
        if (whiteKingCount == 0) placeFallbackKing('K', 7)
        if (blackKingCount == 0) placeFallbackKing('k', 0)
    }

    private fun placeFallbackKing(kingChar: Char, preferredRow: Int) {
        val isWhite = kingChar.isUpperCase()
        if (board[preferredRow][4] == '.' || pieceColor(board[preferredRow][4]) == (if (isWhite) 1 else -1)) {
            board[preferredRow][4] = kingChar
            return
        }
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if ((isWhite && p.isUpperCase()) || (!isWhite && p.isLowerCase())) {
                    board[r][c] = kingChar
                    return
                }
            }
        }
        board[preferredRow][4] = kingChar
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
                    sb.append(piece)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < 7) sb.append("/")
        }
        return "$sb $turn - - 0 1"
    }

    private fun pieceColor(p: Char): Int = when {
        p == '.' -> 0
        p.isUpperCase() -> 1
        else -> -1
    }

    private fun sameOccupancy(a: Array<CharArray>, b: Array<CharArray>): Boolean {
        for (r in 0..7) {
            for (c in 0..7) {
                if (pieceColor(a[r][c]) != pieceColor(b[r][c])) return false
            }
        }
        return true
    }

    private fun isBoardEmpty(): Boolean {
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] != '.') return false
            }
        }
        return true
    }

    private fun copyBoard(src: Array<CharArray>): Array<CharArray> {
        return Array(8) { r -> src[r].clone() }
    }
}
