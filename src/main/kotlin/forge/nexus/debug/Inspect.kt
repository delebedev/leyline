package forge.nexus.debug

import com.google.protobuf.TextFormat
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

/**
 * CLI tool to inspect Arena .bin templates as structured proto text.
 *
 * Usage:
 *   make -C forge-web arena-inspect ARENA_TEMPLATE=path/to/file.bin
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: ArenaInspect <file.bin>")
        System.exit(1)
    }

    val file = File(path!!)
    if (!file.exists()) {
        System.err.println("File not found: $path")
        System.exit(1)
    }

    val bytes = file.readBytes()
    val msg: MatchServiceToClientMessage
    try {
        msg = MatchServiceToClientMessage.parseFrom(bytes)
    } catch (e: Exception) {
        System.err.println("Failed to parse as MatchServiceToClientMessage: ${e.message}")
        System.exit(1)
        return // unreachable, satisfies compiler
    }

    // --- Summary ---
    println("=== ${file.name} (${bytes.size} bytes) ===")
    println()

    val topFields = mutableListOf<String>()
    if (msg.hasAuthenticateResponse()) topFields += "AuthenticateResponse"
    if (msg.hasMatchGameRoomStateChangedEvent()) topFields += "MatchGameRoomStateChangedEvent"
    if (msg.hasGreToClientEvent()) topFields += "GreToClientEvent"

    println("Top-level fields: ${topFields.ifEmpty { listOf("(none)") }.joinToString(", ")}")

    if (msg.hasGreToClientEvent()) {
        val event = msg.greToClientEvent
        val greCount = event.greToClientMessagesCount
        println("GRE messages:     $greCount")
        for (gre in event.greToClientMessagesList) {
            val parts = mutableListOf("type=${gre.type}", "msgId=${gre.msgId}")
            if (gre.gameStateId != 0) parts += "gsId=${gre.gameStateId}"
            if (gre.systemSeatIdsCount > 0) parts += "seats=${gre.systemSeatIdsList}"
            if (gre.hasGameStateMessage()) {
                val gs = gre.gameStateMessage
                parts += "zones=${gs.zonesCount}"
                parts += "objects=${gs.gameObjectsCount}"
                parts += "players=${gs.playersCount}"
            }
            println("  - ${parts.joinToString("  ")}")
        }
    }

    if (msg.hasMatchGameRoomStateChangedEvent()) {
        val info = msg.matchGameRoomStateChangedEvent.gameRoomInfo
        println("Room state:       ${info.stateType}")
        println("Players:          ${info.playersList.map { "${it.playerName}(seat=${it.systemSeatId})" }}")
    }

    val unknownSize = msg.unknownFields.serializedSize
    if (unknownSize > 0) {
        println("Unknown fields:   $unknownSize bytes")
    }

    println()
    println("=== Proto Text ===")
    println(TextFormat.printer().printToString(msg))
}
