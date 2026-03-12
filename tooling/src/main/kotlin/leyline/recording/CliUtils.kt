package leyline.recording

fun parseSeatFilter(args: List<String>, default: Int = 1): Int? {
    val seatIdx = args.indexOf("--seat")
    val rawSeat = if (seatIdx >= 0 && seatIdx + 1 < args.size) {
        args[seatIdx + 1].toIntOrNull() ?: default
    } else {
        default
    }
    return if (rawSeat == 0) null else rawSeat
}

/** Parse --start N / --finish N turn range from CLI args. Null = unbounded. */
data class TurnRange(val start: Int?, val finish: Int?) {
    fun contains(turn: Int): Boolean {
        if (start != null && turn < start) return false
        if (finish != null && turn > finish) return false
        return true
    }

    fun pastFinish(turn: Int): Boolean = finish != null && turn > finish

    val active: Boolean get() = start != null || finish != null
}

fun parseTurnRange(args: List<String>): TurnRange {
    val startIdx = args.indexOf("--start")
    val finishIdx = args.indexOf("--finish")
    val start = if (startIdx >= 0 && startIdx + 1 < args.size) args[startIdx + 1].toIntOrNull() else null
    val finish = if (finishIdx >= 0 && finishIdx + 1 < args.size) args[finishIdx + 1].toIntOrNull() else null
    return TurnRange(start, finish)
}

fun printSeatIdentification(seats: Map<Int, RecordingDecoder.SeatInfo>, err: java.io.PrintStream = System.err) {
    if (seats.isNotEmpty()) {
        err.println("Seat identification:")
        for ((id, info) in seats.toSortedMap()) {
            err.println("  Seat $id: ${info.playerName} (${info.role})")
        }
        err.println()
    }
}
