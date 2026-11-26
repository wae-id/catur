package com.wad.chessoverlay

class ChessLogic {
    val board = Array(8) { CharArray(8) { '.' } }
    var currentTurn = "w"
    private var hasTrackedGame = false
    private var stableCandidate: Array<CharArray>? = null
    private var stableStreak = 0
    private var desyncFrames = 0

    fun reset() {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = '.'
            }
        }
        currentTurn = "w"
        hasTrackedGame = false
        stableCandidate = null
        stableStreak = 0
        desyncFrames = 0
    }

    fun updateFromScan(
        screenBoard: Array<CharArray>,
        isFlipped: Boolean,
        detectedTurn: String?
    ) {
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

        // Butuh 3 frame berturut-turut yang identik agar bebas dari efek animasi geser bidak
        val prev = stableCandidate
        if (prev != null && sameOccupancy(prev, oriented)) {
            stableStreak++
        } else {
            stableCandidate = copyBoard(oriented)
            stableStreak = 1
        }
        if (stableStreak < 3 && !isBoardEmpty()) return

        if (isStartingPosition(oriented)) {
            setStandardStartingBoard()
            currentTurn = "w"
            hasTrackedGame = true
            desyncFrames = 0
            return
        }

        if (hasTrackedGame) {
            if (sameOccupancy(board, oriented)) {
                if (detectedTurn != null) currentTurn = detectedTurn
                desyncFrames = 0
                return
            }

            val mySide = if (isFlipped) -1 else 1
            val oppSide = -mySide

            // Coba cocokkan 1 langkah legal
            val onePlyResult = findBestLegalSequenceWithTurn(
                board,
                oriented,
                listOf(oppSide, mySide),
                maxDepth = 1
            )
            if (onePlyResult != null) {
                copyInto(onePlyResult.first, board)
                currentTurn = detectedTurn ?: onePlyResult.second
                desyncFrames = 0
                return
            }

            // Coba cocokkan 2 langkah legal beruntun
            val twoPlyResult = findBestLegalSequenceWithTurn(
                board,
                oriented,
                listOf(mySide, oppSide),
                maxDepth = 2
            )
            if (twoPlyResult != null) {
                copyInto(twoPlyResult.first, board)
                currentTurn = detectedTurn ?: twoPlyResult.second
                desyncFrames = 0
                return
            }

            desyncFrames++
            if (desyncFrames >= 5) {
                desyncFrames = 0
                syncFromClassifiedBoard(oriented)
                hasTrackedGame = true
                if (detectedTurn != null) currentTurn = detectedTurn
            }
        } else {
            // Saat bot baru dinyalakan di tengah permainan, kunci posisi langsung agar tidak berubah-ubah
            syncFromClassifiedBoard(oriented)
            hasTrackedGame = true
            currentTurn = detectedTurn ?: (if (isFlipped) "b" else "w")
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

    private fun findBestLegalSequenceWithTurn(
        startBoard: Array<CharArray>,
        targetScan: Array<CharArray>,
        firstSides: List<Int>,
        maxDepth: Int
    ): Pair<Array<CharArray>, String>? {
        var bestBoard: Array<CharArray>? = null
        var bestNextTurn = "w"
        var bestScore = -1

        for (side1 in firstSides) {
            val moves1 = generateLegalMoves(startBoard, side1)
            for (b1 in moves1) {
                if (maxDepth == 1) {
                    val score = matchScore(b1, targetScan)
                    if (score > bestScore) {
                        bestScore = score
                        bestBoard = b1
                        bestNextTurn = if (side1 == 1) "b" else "w"
                    }
                } else {
                    val side2 = -side1
                    val moves2 = generateLegalMoves(b1, side2)
                    for (b2 in moves2) {
                        val score = matchScore(b2, targetScan)
                        if (score > bestScore) {
                            bestScore = score
                            bestBoard = b2
                            bestNextTurn = if (side2 == 1) "b" else "w"
                        }
                    }
                }
            }
        }

        return if (bestBoard != null && bestScore >= 62) {
            bestBoard to bestNextTurn
        } else {
            null
        }
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
        val pawnRow = if (side == 1) kr - 1 else kr + 1
        val oppPawn = if (side == 1) 'p' else 'P'
        if (pawnRow in 0..7) {
            if ((kc - 1 in 0..7 && b[pawnRow][kc - 1] == oppPawn) ||
                (kc + 1 in 0..7 && b[pawnRow][kc + 1] == oppPawn)
            ) return true
        }

        val oppKnight = if (side == 1) 'n' else 'N'
        val jumps = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        for ((dr, dc) in jumps) {
            val nr = kr + dr
            val nc = kc + dc
            if (nr in 0..7 && nc in 0..7 && b[nr][nc] == oppKnight) return true
        }

        val oppKing = if (side == 1) 'k' else 'K'
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = kr + dr
                val nc = kc + dc
                if (nr in 0..7 && nc in 0..7 && b[nr][nc] == oppKing) return true
            }
        }

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

        for (c in intArrayOf(4, 5, 3, 6, 2, 1, 7, 0)) {
            if (pieceColor(board[preferredRow][c]) == mySide) {
                board[preferredRow][c] = kingChar
                return
            }
        }
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
        val turnSide = if (turn == "w") 1 else -1
        if (isKingInCheck(board, -turnSide)) return ""

        var wKings = 0
        var bKings = 0
        var wPawns = 0
        var bPawns = 0
        var wTotal = 0
        var bTotal = 0

        val sb = StringBuilder()
        for (r in 0..7) {
            var emptyCount = 0
            for (c in 0..7) {
                val piece = board[r][c]
                if (piece == '.') {
                    emptyCount++
                } else {
                    if (piece.isUpperCase()) wTotal++ else bTotal++
                    when (piece) {
                        'K' -> wKings++
                        'k' -> bKings++
                        'P' -> {
                            if (r == 0 || r == 7) return ""
                            wPawns++
                        }
                        'p' -> {
                            if (r == 0 || r == 7) return ""
                            bPawns++
                        }
                    }
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

        if (wKings != 1 || bKings != 1 || wPawns > 8 || bPawns > 8 || wTotal > 16 || bTotal > 16) {
            return ""
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
