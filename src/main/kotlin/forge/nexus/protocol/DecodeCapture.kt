package forge.nexus.protocol

import forge.nexus.debug.NexusPaths
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

/**
 * Decode Match Door binary payloads.
 * Usage: `just proto-decode` or `just proto-diff`
 */
/**
 * Compare our generated MatchServiceToClientMessage with a reference binary.
 * Parses both, prints field-by-field diffs.
 */
private fun compareWithReal(realFile: File) {
    println("=== Comparing with real capture: ${realFile.name} ===")
    val realMsg = MatchServiceToClientMessage.parseFrom(realFile.readBytes())

    println("Real file size: ${realFile.length()} bytes")
    println("Real message:\n$realMsg")
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--compare") {
        compareWithReal(File(args[1]))
        return
    }
    val dir = File(args.firstOrNull() ?: NexusPaths.CAPTURE_PAYLOADS.absolutePath)
    val files = dir.listFiles()?.filter { it.name.startsWith("S-C_MATCH") }?.sortedBy { it.name } ?: return

    for (file in files) {
        println("=== ${file.name} (${file.length()} bytes) ===")
        try {
            val msg = MatchServiceToClientMessage.parseFrom(file.readBytes())
            // Print a compact summary
            if (msg.hasAuthenticateResponse()) {
                println("  AuthenticateResponse: clientId=${msg.authenticateResponse.clientId}")
            }
            if (msg.hasMatchGameRoomStateChangedEvent()) {
                val ev = msg.matchGameRoomStateChangedEvent
                val info = ev.gameRoomInfo
                println("  MatchGameRoomStateChangedEvent:")
                println("    stateType=${info.stateType}")
                println("    matchId=${info.gameRoomConfig.matchId}")
                println("    players=${info.playersList.map { "${it.playerName}(seat=${it.systemSeatId})" }}")
                println("    reservedPlayers=${info.gameRoomConfig.reservedPlayersList.map { "${it.playerName}(seat=${it.systemSeatId},bot=${it.isBotPlayer})" }}")
                if (info.gameRoomConfig.hasMatchConfig()) {
                    println("    matchConfig=${info.gameRoomConfig.matchConfig}")
                }
                // Print all fields to find hidden ones
                println("    gameRoomInfo.allFields=${info.allFields.keys.map { it.name }}")
                println("    gameRoomConfig.allFields=${info.gameRoomConfig.allFields.keys.map { it.name }}")
            }
            if (msg.hasGreToClientEvent()) {
                val ev = msg.greToClientEvent
                for (gre in ev.greToClientMessagesList) {
                    println("  GRE: type=${gre.type} msgId=${gre.msgId} gsId=${gre.gameStateId} seats=${gre.systemSeatIdsList}")
                    if (gre.hasConnectResp()) {
                        println("    ConnectResp: status=${gre.connectResp.status} protoVer=${gre.connectResp.protoVer}")
                        if (gre.connectResp.hasSettings()) {
                            println("    Settings: ${gre.connectResp.settings.stopsCount} stops")
                        }
                    }
                    if (gre.hasDieRollResultsResp()) {
                        val dr = gre.dieRollResultsResp
                        println("    DieRoll: ${dr.playerDieRollsList.map { "seat${it.systemSeatId}=${it.rollValue}" }}")
                    }
                    if (gre.hasGameStateMessage()) {
                        val gs = gre.gameStateMessage
                        println("    GameState: type=${gs.type} gsId=${gs.gameStateId}")
                        if (gs.hasGameInfo()) {
                            println("      gameInfo: stage=${gs.gameInfo.stage} matchState=${gs.gameInfo.matchState}")
                        }
                        if (gs.hasTurnInfo()) {
                            println("      turnInfo: phase=${gs.turnInfo.phase} step=${gs.turnInfo.step} turn=${gs.turnInfo.turnNumber} active=${gs.turnInfo.activePlayer}")
                        }
                        println("      zones=${gs.zonesCount} objects=${gs.gameObjectsCount} players=${gs.playersCount}")
                        for (z in gs.zonesList) {
                            println("      zone[${z.zoneId}]: type=${z.type} owner=${z.ownerSeatId} vis=${z.visibility} objects=${z.objectInstanceIdsCount}")
                        }
                    }
                    if (gre.hasMulliganReq()) {
                        println("    MulliganReq: type=${gre.mulliganReq.mulliganType}")
                    }
                    if (gre.hasActionsAvailableReq()) {
                        println("    ActionsAvailableReq: ${gre.actionsAvailableReq.actionsCount} actions")
                    }
                    if (gre.hasChooseStartingPlayerReq()) {
                        println("    ChooseStartingPlayerReq")
                    }
                    if (gre.hasPrompt()) {
                        println("    Prompt: id=${gre.prompt.promptId} params=${gre.prompt.parametersCount}")
                        for (p in gre.prompt.parametersList) {
                            println("      ${p.parameterName}: type=${p.type} num=${p.numberValue} str=${p.stringValue} ref=${p.reference}")
                        }
                    }
                }
            }
            // Check for unknown fields
            val unknowns = msg.unknownFields
            if (unknowns.serializedSize > 0) {
                println("  UNKNOWN FIELDS: ${unknowns.serializedSize} bytes")
            }
        } catch (e: Exception) {
            println("  ERROR: ${e.message}")
        }
        println()
    }
}
