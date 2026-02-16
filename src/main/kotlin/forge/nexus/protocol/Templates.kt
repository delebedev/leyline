package forge.nexus.protocol

import forge.nexus.game.CardDb
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Loads protocol template payloads and patches dynamic fields.
 *
 * Why: The .proto schema may not cover every field the client expects.
 * Template+Patch preserves undocumented fields while allowing dynamic values.
 */
object Templates {
    private val log = LoggerFactory.getLogger(Templates::class.java)

    // Cached raw templates (loaded once)
    private val initialBundleSeat1 = loadTemplate("initial-bundle-seat1.bin")
    private val initialBundleSeat2 = loadTemplate("initial-bundle-seat2.bin")
    private val dealHandSeat1 = loadTemplate("deal-hand-seat1.bin")
    private val dealHandMulliganSeat2 = loadTemplate("deal-hand-mulligan-seat2.bin")
    private val mulliganReqSeat1 = loadTemplate("mulligan-req-seat1.bin")
    private val settingsRespSeat1 = loadTemplate("settings-resp-seat1.bin")
    private val settingsRespSeat2 = loadTemplate("settings-resp-seat2.bin")

    private fun loadTemplate(name: String): ByteArray {
        val stream = Templates::class.java.getResourceAsStream("/arena-templates/$name")
            ?: throw IllegalStateException("Missing template: arena-templates/$name")
        return stream.readAllBytes().also {
            log.debug("Loaded template {} ({}B)", name, it.size)
        }
    }

    /** Room state event — built from scratch (no template). */
    fun roomState(matchId: String, playerId: String): MatchServiceToClientMessage {
        val familiarId = "${playerId}_Familiar"

        fun playerInfo(userId: String, name: String, seat: Int, team: Int) =
            MatchGameRoomPlayerInfo.newBuilder()
                .setUserId(userId).setPlayerName(name)
                .setSystemSeatId(seat).setTeamId(team)

        val config = MatchGameRoomConfig.newBuilder()
            .setMatchId(matchId)
            .setEventId("AIBotMatch")
            .addReservedPlayers(
                playerInfo(playerId, "Player", 1, 1)
                    .setCourseId("Avatar_Basic_Adventurer")
                    .setPlatformId("Mac"),
            )
            .addReservedPlayers(
                playerInfo(familiarId, "Sparky", 2, 2)
                    .setCourseId("Avatar_Basic_Sparky")
                    .setIsBotPlayer(true)
                    .setEventId("AIBotMatch"),
            )

        val roomInfo = MatchGameRoomInfo.newBuilder()
            .setGameRoomConfig(config)
            .setStateType(MatchGameRoomStateType.Playing)
            .addPlayers(playerInfo(playerId, "Player", 1, 1))
            .addPlayers(playerInfo(familiarId, "Sparky", 2, 2))

        return MatchServiceToClientMessage.newBuilder()
            .setMatchGameRoomStateChangedEvent(
                MatchGameRoomStateChangedEvent.newBuilder().setGameRoomInfo(roomInfo),
            )
            .build()
    }

    /**
     * Initial GRE bundle (ConnectResp + DieRoll + GameState).
     * Seat 2 also gets ChooseStartingPlayerReq.
     * Patches: matchId in GameInfo, msgIds, gameStateId, seatIds, deckMessage.
     */
    fun initialBundle(
        seatId: Int,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        deckMessage: DeckMessage,
    ): Pair<MatchServiceToClientMessage, Int> {
        val templateBytes = if (seatId == 1) initialBundleSeat1 else initialBundleSeat2
        val template = MatchServiceToClientMessage.parseFrom(templateBytes)
        val event = template.greToClientEvent
        val builder = event.toBuilder()

        var msgId = msgIdStart
        for (i in 0 until builder.greToClientMessagesCount) {
            val gre = builder.getGreToClientMessages(i).toBuilder()
                .setMsgId(msgId++)

            // Patch seat routing
            gre.clearSystemSeatIds()
            when (gre.type) {
                GREMessageType.DieRollResultsResp_695e -> {
                    gre.addSystemSeatIds(1).addSystemSeatIds(2)
                    gre.setGameStateId(0)
                }
                GREMessageType.ConnectResp_695e -> {
                    gre.addSystemSeatIds(seatId)
                    gre.setGameStateId(0)
                    // Patch deck
                    if (gre.hasConnectResp()) {
                        gre.setConnectResp(gre.connectResp.toBuilder().setDeckMessage(deckMessage))
                    }
                }
                GREMessageType.GameStateMessage_695e -> {
                    gre.addSystemSeatIds(seatId)
                    gre.setGameStateId(gameStateId)
                    if (gre.hasGameStateMessage()) {
                        val gs = gre.gameStateMessage.toBuilder()
                            .setGameStateId(gameStateId)
                        if (gs.hasGameInfo()) {
                            gs.setGameInfo(gs.gameInfo.toBuilder().setMatchID(matchId))
                        }
                        // Clear stale card objects/actions from template
                        gs.clearGameObjects()
                        gs.clearActions()
                        gre.setGameStateMessage(gs)
                    }
                }
                GREMessageType.ChooseStartingPlayerReq_695e -> {
                    gre.addSystemSeatIds(seatId)
                    gre.setGameStateId(gameStateId)
                }
                else -> {
                    gre.addSystemSeatIds(seatId)
                }
            }
            builder.setGreToClientMessages(i, gre)
        }

        val result = template.toBuilder()
            .setGreToClientEvent(builder)
            .build()
        return result to msgId
    }

    /**
     * DealHand for seat 1 (no MulliganReq, decision=2).
     * Each card slot gets its own grpId from [handGrpIds].
     */
    fun dealHandSeat1(msgId: Int, gameStateId: Int, handGrpIds: List<Int>): Pair<MatchServiceToClientMessage, Int> {
        val template = MatchServiceToClientMessage.parseFrom(dealHandSeat1)
        val patched = patchGrpIds(template, handGrpIds)
        return patchSingleGRE(patched, msgId, gameStateId, 1)
    }

    /**
     * DealHand + MulliganReq for seat 2 (decision=2).
     * Each card slot gets its own grpId from [handGrpIds].
     */
    fun dealHandMulliganSeat2(msgIdStart: Int, gameStateId: Int, handGrpIds: List<Int>): Pair<MatchServiceToClientMessage, Int> {
        val template = MatchServiceToClientMessage.parseFrom(dealHandMulliganSeat2)
        val patched = patchGrpIds(template, handGrpIds)
        return patchMultiGRE(patched, msgIdStart, gameStateId, 2)
    }

    /**
     * MulliganReq sequence for seat 1: GameState(decision=1) + PromptReq + MulliganReq.
     * This is the critical template — contains unknown fields that trigger the mulligan UI.
     */
    fun mulliganReqSeat1(msgIdStart: Int, gameStateId: Int): Pair<MatchServiceToClientMessage, Int> {
        val template = MatchServiceToClientMessage.parseFrom(mulliganReqSeat1)
        val event = template.greToClientEvent.toBuilder()
        var msgId = msgIdStart

        for (i in 0 until event.greToClientMessagesCount) {
            val gre = event.getGreToClientMessages(i).toBuilder()
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)

            // Preserve original seatIds — template has correct routing
            if (gre.hasGameStateMessage()) {
                val gs = gre.gameStateMessage.toBuilder().setGameStateId(gameStateId)
                // Clear stale card objects/actions from template
                gs.clearGameObjects()
                gs.clearActions()
                gre.setGameStateMessage(gs)
            }
            event.setGreToClientMessages(i, gre)
        }

        val result = template.toBuilder()
            .setGreToClientEvent(event)
            .build()
        return result to msgId
    }

    /** SetSettingsResp — echo client settings on top of template. */
    fun settingsResp(
        seatId: Int,
        msgId: Int,
        gameStateId: Int,
        clientSettings: SettingsMessage?,
    ): Pair<MatchServiceToClientMessage, Int> {
        val templateBytes = if (seatId == 1) settingsRespSeat1 else settingsRespSeat2
        val template = MatchServiceToClientMessage.parseFrom(templateBytes)
        val (result, nextMsgId) = patchSingleGRE(template, msgId, gameStateId, seatId)

        // If client sent settings, echo them back on the template
        if (clientSettings != null) {
            val event = result.greToClientEvent
            val gre = event.getGreToClientMessages(0).toBuilder()
            if (gre.hasSetSettingsResp()) {
                gre.setSetSettingsResp(gre.setSettingsResp.toBuilder().setSettings(clientSettings))
            }
            return result.toBuilder()
                .setGreToClientEvent(event.toBuilder().setGreToClientMessages(0, gre))
                .build() to nextMsgId
        }
        return result to nextMsgId
    }

    // --- grpId patching ---

    /**
     * Patch gameObjects with per-card grpIds from the dealt hand.
     * gameObject[i] gets handGrpIds[i]; if i >= list size, uses last element.
     * Actions are cleared — template actions have stale costs from template
     * that trigger "Cost Modified" overlay in the client.
     */
    private fun patchGrpIds(msg: MatchServiceToClientMessage, handGrpIds: List<Int>): MatchServiceToClientMessage {
        if (handGrpIds.isEmpty()) return msg
        val event = msg.greToClientEvent.toBuilder()
        for (i in 0 until event.greToClientMessagesCount) {
            val gre = event.getGreToClientMessages(i)
            if (!gre.hasGameStateMessage()) continue
            val gs = gre.gameStateMessage.toBuilder()

            // Patch game objects — each slot gets its own grpId
            for (j in 0 until gs.gameObjectsCount) {
                val grpId = handGrpIds.getOrElse(j) { handGrpIds.last() }
                gs.setGameObjects(j, CardDb.buildObjectInfo(grpId, gs.getGameObjects(j)))
            }

            // Clear template actions — stale mana costs cause "Cost Modified" display
            gs.clearActions()

            event.setGreToClientMessages(i, gre.toBuilder().setGameStateMessage(gs))
        }
        return msg.toBuilder().setGreToClientEvent(event).build()
    }

    // --- helpers ---

    private fun patchSingleGRE(
        template: MatchServiceToClientMessage,
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
    ): Pair<MatchServiceToClientMessage, Int> {
        val event = template.greToClientEvent.toBuilder()
        val gre = event.getGreToClientMessages(0).toBuilder()
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .clearSystemSeatIds()
            .addSystemSeatIds(seatId)

        if (gre.hasGameStateMessage()) {
            gre.setGameStateMessage(
                gre.gameStateMessage.toBuilder().setGameStateId(gameStateId),
            )
        }
        event.setGreToClientMessages(0, gre)
        return template.toBuilder()
            .setGreToClientEvent(event)
            .build() to (msgId + 1)
    }

    private fun patchMultiGRE(
        template: MatchServiceToClientMessage,
        msgIdStart: Int,
        gameStateId: Int,
        seatId: Int,
    ): Pair<MatchServiceToClientMessage, Int> {
        val event = template.greToClientEvent.toBuilder()
        var msgId = msgIdStart

        for (i in 0 until event.greToClientMessagesCount) {
            val gre = event.getGreToClientMessages(i).toBuilder()
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .clearSystemSeatIds()
                .addSystemSeatIds(seatId)

            if (gre.hasGameStateMessage()) {
                gre.setGameStateMessage(
                    gre.gameStateMessage.toBuilder().setGameStateId(gameStateId),
                )
            }
            event.setGreToClientMessages(i, gre)
        }

        return template.toBuilder()
            .setGreToClientEvent(event)
            .build() to msgId
    }
}
