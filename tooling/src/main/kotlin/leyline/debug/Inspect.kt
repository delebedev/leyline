package leyline.debug

import com.google.protobuf.TextFormat
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI tool to inspect client .bin templates as structured proto text.
 *
 * Usage:
 *   make -C forge-web arena-inspect ARENA_TEMPLATE=path/to/file.bin
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: proto-inspect <file.bin>")
        exitProcess(1)
    }

    val file = File(path!!)
    if (!file.exists()) {
        System.err.println("File not found: $path")
        exitProcess(1)
    }

    val bytes = file.readBytes()

    println("=== ${file.name} (${bytes.size} bytes) ===")
    println()

    // Detect direction: parse outer ClientToMatchServiceMessage and check the message type enum.
    // ClientToGremessage=2 and ClientToGreuimessage=3 are C→S GRE payloads.
    // Any other value (or parse failure) → treat as S→C MatchServiceToClientMessage.
    val outer = try { ClientToMatchServiceMessage.parseFrom(bytes) } catch (e: Exception) { null }
    val isCS = outer != null && (outer.clientToMatchServiceMessageType.number == 2 ||
        outer.clientToMatchServiceMessageType.number == 3)

    if (isCS) {
        val inner = try {
            ClientToGREMessage.parseFrom(outer!!.payload)
        } catch (e: Exception) {
            System.err.println("Failed to parse payload as ClientToGREMessage: ${e.message}")
            exitProcess(1)
            return
        }
        printClientToGRE(inner)
        return
    }

    // S→C path
    val sc = try {
        MatchServiceToClientMessage.parseFrom(bytes)
    } catch (e: Exception) {
        System.err.println("Failed to parse as MatchServiceToClientMessage: ${e.message}")
        exitProcess(1)
        return
    }
    printServerToClient(sc)
}

private fun printServerToClient(msg: MatchServiceToClientMessage) {
    val topFields = buildList {
        if (msg.hasAuthenticateResponse()) add("AuthenticateResponse")
        if (msg.hasMatchGameRoomStateChangedEvent()) add("MatchGameRoomStateChangedEvent")
        if (msg.hasGreToClientEvent()) add("GreToClientEvent")
    }

    println("Top-level fields: ${topFields.ifEmpty { listOf("(none)") }.joinToString(", ")}")

    if (msg.hasGreToClientEvent()) {
        val event = msg.greToClientEvent
        val greCount = event.greToClientMessagesCount
        println("GRE messages:     $greCount")
        for (gre in event.greToClientMessagesList) {
            val parts = buildList {
                add("type=${gre.type}")
                add("msgId=${gre.msgId}")
                if (gre.gameStateId != 0) add("gsId=${gre.gameStateId}")
                if (gre.systemSeatIdsCount > 0) add("seats=${gre.systemSeatIdsList}")
                if (gre.hasGameStateMessage()) {
                    val gs = gre.gameStateMessage
                    add("zones=${gs.zonesCount}")
                    add("objects=${gs.gameObjectsCount}")
                    add("players=${gs.playersCount}")
                }
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

private fun printClientToGRE(msg: ClientToGREMessage) {
    val innerField = msg.messageCase?.name ?: "(none)"
    println("Top-level fields: ClientToGREMessage")
    println("type=${msg.type}  seatId=${msg.systemSeatId}  gsId=${msg.gameStateId}  respId=${msg.respId}")
    println("message:          $innerField")

    if (msg.hasOptionalResp()) println("  response=${msg.optionalResp.response}")

    println()
    println("=== Proto Text (ClientToGREMessage) ===")
    println(TextFormat.printer().printToString(msg))
}
