package forge.nexus.recording

fun parseSeatFilter(args: List<String>, default: Int = 1): Int? {
    val seatIdx = args.indexOf("--seat")
    val rawSeat = if (seatIdx >= 0 && seatIdx + 1 < args.size) {
        args[seatIdx + 1].toIntOrNull() ?: default
    } else {
        default
    }
    return if (rawSeat == 0) null else rawSeat
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
