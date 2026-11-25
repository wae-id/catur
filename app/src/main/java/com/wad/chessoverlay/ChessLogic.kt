package com.wad.chessoverlay

import kotlin.math.abs

class ChessLogic {
    val board = Array(8) { CharArray(8) { '.' } }
    private var hasTrackedGame = false
    private var stableCandidate: Array<CharArray>? = null
    private var stableStreak = 0

    fun reset() {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = '.'
            }
        }
        hasTrackedGame = false
        stableCandidate = null
        stableStreak = 0
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

        // Pastikan tampilan papan stabil minimal 2 frame berturut-turut (abaikan animasi geser)
        val prev = stableCandidate
        if (prev != null && sameOccupancy(prev, oriented)) {
            stableStreak++
        } else {
            stableCandidate = copyBoard(oriented)
            stableStreak = 1
        }
        if (stableStreak < 2 && !isBoardEmpty()) return

        // Jika papan kembali ke posisi awal permainan, muat ulang 32 bidak standar
        if (isStartingPosition(oriented)) {
            setStandardStartingBoard()
            hasTrackedGame = true
            return
        }

        if (hasTrackedGame) {
            // Jika tidak ada perubahan warna/posisi petak, lewati
            if (sameOccupancy(board, oriented)) return

            val mySide = if (isFlipped) -1 else 1
            val oppSide = -mySide

            // 1. Coba cocokkan 1 langkah legal (lawan baru jalan atau kita baru jalan)
            val onePly = findBestLegalSequence(board, oriented, listOf(oppSide, mySide), maxDepth = 1)
            if (onePly != null) {
                copyInto(onePly, board)
                return
            }

            // 2. Coba cocokkan 2 langkah legal beruntun (kita jalan + Computer langsung balas)
            val twoPly = findBestLegalSequence(board, oriented, listOf(mySide, oppSide), maxDepth = 2)
            if (twoPly != null) {
                copyInto(twoPly, board)
                return
            }

            // 3. Jika terjadi lompatan jauh (misal muat ulang game di tengah jalan), sinkronisasi ulang
            if (countOccupancyDiff(board, oriented) >= 6) {
                hasTrackedGame = false
                syncFromClassifiedBoard(oriented)
            }
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

    private fun findBestLegalSequence(
        startBoard: Array<CharArray>,
        targetScan: Array<CharArray>,
        firstSides: List<Int>,
        maxDepth: Int
    ): Array<CharArray>? {
        var bestBoard: Array<CharArray>? = null
        var bestScore = -1

        for (side1 in firstSides) {
            val moves1 = generateLegalMoves(startBoard, side1)
            for (b1 in moves1) {
                if (maxDepth == 1) {
                    val score = matchScore(b1, targetScan)
                    if (score > bestScore) {
                        bestScore = score
                        bestBoard = b1
                    }
                } else {
                    val moves2 = generateLegalMoves(b1, -side1)
                    for (b2 in moves2) {
                        val score = matchScore(b2, targetScan)
                        if (score > bestScore) {
                            bestScore = score
                            bestBoard = b2
                        }
                    }
                }
            }
        }

        // Terima hasil jika minimal 63 dari 64 petak cocok warna & isinya
        return if (bestScore >= 63) bestBoard else null
    }

    private fun matchScore(candidate: Array<CharArray>, scan: Array<CharArray>): Int {
        var matches = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (pieceColor(candidate[r][c]) == pieceColor(scan[r][c])) {
                    matches++
                }
            }
        }
        return matches
    }

    private fun generateLegalMoves(b: Array<CharArray>, side: Int): List<Array<CharArray>> {
        val result = mutableListOf<Array<CharArray>>()
        val isWhite = (side == 1)

        for (r in 0..7) {
            for (c in 0..7) {
                val p = b[r][c]
                if (pieceColor(p) != side) continue

                when (p.uppercaseChar()) {
                    'P' -> {
                        val dir = if (isWhite) -1 else 1
                        val startRow = if (isWhite) 6 else 1
                        val promoRow = if (isWhite) 0 else 7
                        val nr = r + dir

                        if (nr in 0..7 && b[nr][c] == '.') {
                            result.add(applyMove(b, r, c, nr, c, if (nr == promoRow) (if (isWhite) 'Q' else 'q') else p))
                            val nnr = r + 2 * dir
                            if (r == startRow && nnr in 0..7 && b[nnr][c] == '.') {
                                result.add(applyMove(b, r, c, nnr, c, p))
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val targetColor = pieceColor(b[nr][nc])
                                if (targetColor == -side) {
                                    result.add(applyMove(b, r, c, nr, nc, if (nr == promoRow) (if (isWhite) 'Q' else 'q') else p))
                                } else if (targetColor == 0 && r == (if (isWhite) 3 else 4) &&
                                    b[r][nc] == (if (isWhite) 'p' else 'P')
                                ) {
                                    val ep = applyMove(b, r, c, nr, nc, p)
                                    ep[r][nc] = '.'
                                    result.add(ep)
                                }
                            }
                        }
                    }
                    'N' -> {
                        val jumps = arrayOf(
                            -2 to -1, -2 to 1, -1 to -2, -1 to 2,
                            1 to -2, 1 to 2, 2 to -1, 2 to 1
                        )
                        for ((dr, dc) in jumps) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7 && pieceColor(b[nr][nc]) != side) {
                                result.add(applyMove(b, r, c, nr, nc, p))
                            }
                        }
                    }
                    'B', 'R', 'Q' -> {
                        val dirs = mutableListOf<Pair<Int, Int>>()
                        if (p.uppercaseChar() != 'R') {
                            dirs.addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
                        }
                        if (p.uppercaseChar() != 'B') {
                            dirs.addAll(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                        }
                        for ((dr, dc) in dirs) {
                            var nr = r + dr
                            var nc = c + dc
                            while (nr in 0..7 && nc in 0..7) {
                                val tc = pieceColor(b[nr][nc])
                                if (tc == side) break
                                result.add(applyMove(b, r, c, nr, nc, p))
                                if (tc == -side) break
                                nr += dr
                                nc += dc
                            }
                        }
                    }
                    'K' -> {
                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = r + dr
                                val nc = c + dc
                                if (nr in 0..7 && nc in 0..7 && pieceColor(b[nr][nc]) != side) {
                                    result.add(applyMove(b, r, c, nr, nc, p))
                                }
                            }
                        }
                        // Rokade standar (King di kolom 4)
                        val homeRow = if (isWhite) 7 else 0
                        val rookChar = if (isWhite) 'R' else 'r'
                        if (r == homeRow && c == 4 && !isKingInCheck(b, side)) {
                            if (b[homeRow][7] == rookChar && b[homeRow][5] == '.' && b[homeRow][6] == '.') {
                                val step1 = applyMove(b, homeRow, 4, homeRow, 5, p)
                                if (!isKingInCheck(step1, side)) {
                                    val castleK = applyMove(b, homeRow, 4, homeRow, 6, p)
                                    castleK[homeRow][7] = '.'
                                    castleK[homeRow][5] = rookChar
                                    result.add(castleK)
                                }
                            }
                            if (b[homeRow][0] == rookChar && b[homeRow][1] == '.' &&
                                b[homeRow][2] == '.' && b[homeRow][3] == '.'
                            ) {
                                val step1 = applyMove(b, homeRow, 4, homeRow, 3, p)
                                if (!isKingInCheck(step1, side)) {
                                    val castleQ = applyMove(b, homeRow, 4, homeRow, 2, p)
                                    castleQ[homeRow][0] = '.'
                                    castleQ[homeRow][3] = rookChar
                                    result.add(castleQ)
                                }
                            }
                        }
                    }
                }
            }
        }

        return result.filter { !isKingInCheck(it, side) }
    }

    private fun applyMove(
        b: Array<CharArray>,
        fr: Int,
        fc: Int,
        tr: Int,
        tc: Int,
        piece: Char
    ): Array<CharArray> {
        val next = copyBoard(b)
        next[fr][fc] = '.'
        next[tr][tc] = piece
        return next
    }

    private fun isKingInCheck(b: Array<CharArray>, side: Int): Boolean {
        val kingChar = if (side == 1) 'K' else 'k'
        var kr = -1
        var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (b[r][c] == kingChar) {
                    kr = r
                    kc = c
                    break
                }
            }
        }
        if (kr == -1) return true

        val opp = -side
        // Serangan pion lawan
        val pawnRow = if (side == 1) kr - 1 else kr + 1
        val oppPawn = if (side == 1) 'p' else 'P'
        if (pawnRow in 0..7) {
            if ((kc - 1 in 0..7 && b[pawnRow][kc - 1] == oppPawn) ||
                (kc + 1 in 0..7 && b[pawnRow][kc + 1] == oppPawn)
            ) return true
        }

        // Serangan kuda lawan
        val oppKnight = if (side == 1) 'n' else 'N'
        val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        for ((dr, dc) in jumps) {
            val nr = kr + dr
            val nc = kc + dc
            if (nr in 0..7 && nc in 0..7 && b[nr][nc] == oppKnight) return true
        }

        // Serangan raja lawan
        val oppKing = if (side == 1) 'k' else 'K'
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = kr + dr
                val nc = kc + dc
                if (nr in 0..7 && nc in 0..7 && b[nr][nc] == oppKing) return true
            }
        }

        // Serangan garis lurus & diagonal (Benteng, Gajah, Menteri)
        val straight = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in straight) {
            var nr = kr + dr
            var nc = kc + dc
            while (nr in 0..7 && nc in 0..7) {
                val p = b[nr][nc]
                if (p != '.') {
                    if (pieceColor(p) == opp && (p.uppercaseChar() == 'R' || p.uppercaseChar() == 'Q')) return true
                    break
                }
                nr += dr
                nc += dc
            }
        }

        val diag = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for ((dr, dc) in diag) {
            var nr = kr + dr
            var nc = kc + dc
            while (nr in 0..7 && nc in 0..7) {
                val p = b[nr][nc]
                if (p != '.') {
                    if (pieceColor(p) == opp && (p.uppercaseChar() == 'B' || p.uppercaseChar() == 'Q')) return true
                    break
                }
                nr += dr
                nc += dc
            }
        }

        return false
    }

    private fun syncFromClassifiedBoard(classified: Array<CharArray>) {
        var whiteKingCount = 0
        var blackKingCount = 0

        for (r in 0..7) {
            for (c in 0..7) {
                var p = classified[r][c]
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

        if (whiteKingCount == 0) placeFallbackKing('K', 7)
        if (blackKingCount == 0) placeFallbackKing('k', 0)
    }

    private fun placeFallbackKing(kingChar: Char, preferredRow: Int) {
        val isWhite = kingChar.isUpperCase()
        val mySide = if (isWhite) 1 else -1

        // Prioritaskan bidak milik sendiri yang berada di baris belakang (row 7 / row 0)
        for (c in intArrayOf(4, 5, 3, 6, 2, 1, 7, 0)) {
            if (pieceColor(board[preferredRow][c]) == mySide) {
                board[preferredRow][c] = kingChar
                return
            }
        }
        // Cari bidak milik sendiri di baris mana pun
        for (r in 7 downTo 0) {
            val row = if (isWhite) r else 7 - r
            for (c in 0..7) {
                if (pieceColor(board[row][c]) == mySide) {
                    board[row][c] = kingChar
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

    private fun countOccupancyDiff(a: Array<CharArray>, b: Array<CharArray>): Int {
        var diff = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (pieceColor(a[r][c]) != pieceColor(b[r][c])) diff++
            }
        }
        return diff
    }

    private fun isBoardEmpty(): Boolean {
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] != '.') return false
            }
        }
        return true
    }

    private fun copyBoard(src: Array<CharArray>): Array<CharArray> =
        Array(8) { r -> src[r].clone() }

    private fun copyInto(src: Array<CharArray>, dst: Array<CharArray>) {
        for (r in 0..7) {
            for (c in 0..7) {
                dst[r][c] = src[r][c]
            }
        }
    }
}
