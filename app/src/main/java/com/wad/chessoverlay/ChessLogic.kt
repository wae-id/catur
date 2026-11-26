package com.wad.chessoverlay

import kotlin.math.abs

class ChessLogic {
    val board = Array(8) { CharArray(8) { '.' } }
    var currentTurn = "w"

    private var canWhiteCastleK = true
    private var canWhiteCastleQ = true
    private var canBlackCastleK = true
    private var canBlackCastleQ = true
    private var enPassantSquare = "-"

    private var hasTrackedGame = false
    private var stableCandidate: Array<CharArray>? = null
    private var stableStreak = 0
    private var desyncFrames = 0

    private data class MoveCandidate(
        val board: Array<CharArray>,
        val fromRow: Int,
        val fromCol: Int,
        val toRow: Int,
        val toCol: Int,
        val wCastleK: Boolean,
        val wCastleQ: Boolean,
        val bCastleK: Boolean,
        val bCastleQ: Boolean,
        val epSquare: String
    )

    fun reset() {
        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = '.'
            }
        }
        currentTurn = "w"
        canWhiteCastleK = true
        canWhiteCastleQ = true
        canBlackCastleK = true
        canBlackCastleQ = true
        enPassantSquare = "-"
        hasTrackedGame = false
        stableCandidate = null
        stableStreak = 0
        desyncFrames = 0
    }

    fun updateFromScan(
        screenBoard: Array<CharArray>,
        isFlipped: Boolean,
        detectedTurn: String?,
        highlightedSquares: Set<Pair<Int, Int>> = emptySet()
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

            val onePlyResult = findBestLegalSequenceWithTurn(
                oriented,
                listOf(oppSide, mySide),
                maxDepth = 1,
                highlights = highlightedSquares
            )
            if (onePlyResult != null) {
                applyChosenState(onePlyResult.first, detectedTurn ?: onePlyResult.second)
                desyncFrames = 0
                return
            }

            val twoPlyResult = findBestLegalSequenceWithTurn(
                oriented,
                listOf(mySide, oppSide),
                maxDepth = 2,
                highlights = highlightedSquares
            )
            if (twoPlyResult != null) {
                applyChosenState(twoPlyResult.first, detectedTurn ?: twoPlyResult.second)
                desyncFrames = 0
                return
            }

            desyncFrames++
            if (desyncFrames >= 6) {
                desyncFrames = 0
                syncFromClassifiedBoard(oriented)
                hasTrackedGame = true
                if (detectedTurn != null) currentTurn = detectedTurn
            }
        } else {
            syncFromClassifiedBoard(oriented)
            hasTrackedGame = true
            currentTurn = detectedTurn ?: (if (isFlipped) "b" else "w")
        }
    }

    private fun applyChosenState(state: MoveCandidate, nextTurn: String) {
        copyInto(state.board, board)
        canWhiteCastleK = state.wCastleK
        canWhiteCastleQ = state.wCastleQ
        canBlackCastleK = state.bCastleK
        canBlackCastleQ = state.bCastleQ
        enPassantSquare = state.epSquare
        currentTurn = nextTurn
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
        canWhiteCastleK = true
        canWhiteCastleQ = true
        canBlackCastleK = true
        canBlackCastleQ = true
        enPassantSquare = "-"
    }

    private fun findBestLegalSequenceWithTurn(
        targetScan: Array<CharArray>,
        firstSides: List<Int>,
        maxDepth: Int,
        highlights: Set<Pair<Int, Int>>
    ): Pair<MoveCandidate, String>? {
        var bestCandidate: MoveCandidate? = null
        var bestNextTurn = "w"
        var bestOccupancyMatches = -1
        var bestTotalScore = -100.0

        val rootState = MoveCandidate(
            board = board,
            fromRow = -1,
            fromCol = -1,
            toRow = -1,
            toCol = -1,
            wCastleK = canWhiteCastleK,
            wCastleQ = canWhiteCastleQ,
            bCastleK = canBlackCastleK,
            bCastleQ = canBlackCastleQ,
            epSquare = enPassantSquare
        )

        for (side1 in firstSides) {
            val moves1 = generateLegalMoves(rootState, side1)
            for (m1 in moves1) {
                if (maxDepth == 1) {
                    val (occMatches, totalScore) = evaluateMatch(m1, targetScan, highlights)
                    if (occMatches >= 62 && totalScore > bestTotalScore) {
                        bestOccupancyMatches = occMatches
                        bestTotalScore = totalScore
                        bestCandidate = m1
                        bestNextTurn = if (side1 == 1) "b" else "w"
                    }
                } else {
                    val side2 = -side1
                    val moves2 = generateLegalMoves(m1, side2)
                    for (m2 in moves2) {
                        val (occMatches, totalScore) = evaluateMatch(m2, targetScan, highlights)
                        if (occMatches >= 62 && totalScore > bestTotalScore) {
                            bestOccupancyMatches = occMatches
                            bestTotalScore = totalScore
                            bestCandidate = m2
                            bestNextTurn = if (side2 == 1) "b" else "w"
                        }
                    }
                }
            }
        }

        return if (bestCandidate != null && bestOccupancyMatches >= 62) {
            bestCandidate to bestNextTurn
        } else {
            null
        }
    }

    private fun evaluateMatch(
        candidate: MoveCandidate,
        scan: Array<CharArray>,
        highlights: Set<Pair<Int, Int>>
    ): Pair<Int, Double> {
        var occMatches = 0
        var shapeBonus = 0.0

        for (r in 0..7) {
            for (c in 0..7) {
                val candP = candidate.board[r][c]
                val scanP = scan[r][c]
                if (pieceColor(candP) == pieceColor(scanP)) {
                    occMatches++
                    if (candP != '.' && candP == scanP) {
                        shapeBonus += 0.35
                    }
                }
            }
        }

        // Bonus besar jika petak asal & tujuan cocok dengan sorotan kuning di layar
        var highlightBonus = 0.0
        if (highlights.size == 2 && candidate.fromRow in 0..7) {
            if ((candidate.fromRow to candidate.fromCol) in highlights) highlightBonus += 2.5
            if ((candidate.toRow to candidate.toCol) in highlights) highlightBonus += 2.5
        }

        return occMatches to (occMatches.toDouble() + shapeBonus + highlightBonus)
    }

    private fun generateLegalMoves(state: MoveCandidate, side: Int): List<MoveCandidate> {
        val b = state.board
        val result = mutableListOf<MoveCandidate>()
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
                            result.add(buildNextState(state, r, c, nr, c, if (nr == promoRow) (if (isWhite) 'Q' else 'q') else p))
                            val nnr = r + 2 * dir
                            if (r == startRow && nnr in 0..7 && b[nnr][c] == '.') {
                                val epTarget = squareName(nr, c)
                                result.add(buildNextState(state, r, c, nnr, c, p, customEp = epTarget))
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val targetColor = pieceColor(b[nr][nc])
                                if (targetColor == -side) {
                                    result.add(buildNextState(state, r, c, nr, nc, if (nr == promoRow) (if (isWhite) 'Q' else 'q') else p))
                                } else if (targetColor == 0 && r == (if (isWhite) 3 else 4) &&
                                    b[r][nc] == (if (isWhite) 'p' else 'P')
                                ) {
                                    val epState = buildNextState(state, r, c, nr, nc, p)
                                    epState.board[r][nc] = '.'
                                    result.add(epState)
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
                                result.add(buildNextState(state, r, c, nr, nc, p))
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
                                result.add(buildNextState(state, r, c, nr, nc, p))
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
                                    result.add(buildNextState(state, r, c, nr, nc, p))
                                }
                            }
                        }
                        val homeRow = if (isWhite) 7 else 0
                        val rookChar = if (isWhite) 'R' else 'r'
                        val canK = if (isWhite) state.wCastleK else state.bCastleK
                        val canQ = if (isWhite) state.wCastleQ else state.bCastleQ

                        if (r == homeRow && c == 4 && !isKingInCheck(b, side)) {
                            if (canK && b[homeRow][7] == rookChar && b[homeRow][5] == '.' && b[homeRow][6] == '.') {
                                val step1 = buildNextState(state, homeRow, 4, homeRow, 5, p)
                                if (!isKingInCheck(step1.board, side)) {
                                    val castleK = buildNextState(state, homeRow, 4, homeRow, 6, p)
                                    castleK.board[homeRow][7] = '.'
                                    castleK.board[homeRow][5] = rookChar
                                    result.add(castleK)
                                }
                            }
                            if (canQ && b[homeRow][0] == rookChar && b[homeRow][1] == '.' &&
                                b[homeRow][2] == '.' && b[homeRow][3] == '.'
                            ) {
                                val step1 = buildNextState(state, homeRow, 4, homeRow, 3, p)
                                if (!isKingInCheck(step1.board, side)) {
                                    val castleQ = buildNextState(state, homeRow, 4, homeRow, 2, p)
                                    castleQ.board[homeRow][0] = '.'
                                    castleQ.board[homeRow][3] = rookChar
                                    result.add(castleQ)
                                }
                            }
                        }
                    }
                }
            }
        }

        return result.filter { !isKingInCheck(it.board, side) }
    }

    private fun buildNextState(
        prev: MoveCandidate,
        fr: Int,
        fc: Int,
        tr: Int,
        tc: Int,
        piece: Char,
        customEp: String = "-"
    ): MoveCandidate {
        val nextBoard = copyBoard(prev.board)
        nextBoard[fr][fc] = '.'
        nextBoard[tr][tc] = piece

        var wK = prev.wCastleK
        var wQ = prev.wCastleQ
        var bK = prev.bCastleK
        var bQ = prev.bCastleQ

        if (fr == 7 && fc == 4) { wK = false; wQ = false }
        if (fr == 0 && fc == 4) { bK = false; bQ = false }
        if ((fr == 7 && fc == 7) || (tr == 7 && tc == 7)) wK = false
        if ((fr == 7 && fc == 0) || (tr == 7 && tc == 0)) wQ = false
        if ((fr == 0 && fc == 7) || (tr == 0 && tc == 7)) bK = false
        if ((fr == 0 && fc == 0) || (tr == 0 && tc == 0)) bQ = false

        return MoveCandidate(
            board = nextBoard,
            fromRow = fr,
            fromCol = fc,
            toRow = tr,
            toCol = tc,
            wCastleK = wK,
            wCastleQ = wQ,
            bCastleK = bK,
            bCastleQ = bQ,
            epSquare = customEp
        )
    }

    private fun squareName(r: Int, c: Int): String {
        val file = ('a' + c)
        val rank = (8 - r)
        return "$file$rank"
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

        // Simpulkan hak rokade dari posisi Raja & Benteng saat sinkronisasi tengah game
        canWhiteCastleK = (board[7][4] == 'K' && board[7][7] == 'R')
        canWhiteCastleQ = (board[7][4] == 'K' && board[7][0] == 'R')
        canBlackCastleK = (board[0][4] == 'k' && board[0][7] == 'r')
        canBlackCastleQ = (board[0][4] == 'k' && board[0][0] == 'r')
        enPassantSquare = "-"
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

        val castleSb = StringBuilder()
        if (canWhiteCastleK && board[7][4] == 'K' && board[7][7] == 'R') castleSb.append('K')
        if (canWhiteCastleQ && board[7][4] == 'K' && board[7][0] == 'R') castleSb.append('Q')
        if (canBlackCastleK && board[0][4] == 'k' && board[0][7] == 'r') castleSb.append('k')
        if (canBlackCastleQ && board[0][4] == 'k' && board[0][0] == 'r') castleSb.append('q')
        val castleStr = if (castleSb.isEmpty()) "-" else castleSb.toString()

        return "$sb $turn $castleStr $enPassantSquare 0 1"
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
