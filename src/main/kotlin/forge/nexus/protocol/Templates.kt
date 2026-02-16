package forge.nexus.protocol

import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
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
     * DealHand for seat 1 (no MulliganReq) — built from game state.
     */
    fun dealHandSeat1(
        msgId: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        val gsm = StateMapper.buildDealHand(bridge, gameStateId, 1)
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(1)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setGameStateMessage(gsm)
            .build()
        return wrapGre(gre) to (msgId + 1)
    }

    /**
     * DealHand + MulliganReq bundled for seat 2 — built from game state.
     */
    fun dealHandMulliganSeat2(
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart
        val gsm = StateMapper.buildDealHand(bridge, gameStateId, 2)
            .toBuilder().setPendingMessageCount(1).build()
        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(2)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setGameStateMessage(gsm)
            .build()
        val greMull = StateMapper.buildMulliganReq(msgId++, gameStateId, 2)
        return wrapGre(greGsm, greMull) to msgId
    }

    /**
     * MulliganReq sequence for seat 1: GameState(decision=1) + PromptReq + MulliganReq.
     * Built dynamically — no template needed.
     */
    fun mulliganReqSeat1(
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart

        // 1) Thin GSM Diff: seat 2 no longer pending, decisionPlayer=1
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(
                StateMapper.buildPlayerInfo(bridge.getPlayer(2), 2),
            )
            .setTurnInfo(
                TurnInfo.newBuilder().setActivePlayer(2).setDecisionPlayer(1),
            )
            .setPendingMessageCount(2)
            .setPrevGameStateId(gameStateId - 1)
            .addAllTimers(StateMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
            .build()
        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(1)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setGameStateMessage(gsm)
            .build()

        // 2) PromptReq: "who's going first" notification (promptId=37, PlayerId→seat 2)
        val grePrompt = GREToClientMessage.newBuilder()
            .setType(GREMessageType.PromptReq)
            .addSystemSeatIds(1).addSystemSeatIds(2)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(37)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("PlayerId")
                            .setType(ParameterType.Reference_a14a)
                            .setReference(
                                Reference.newBuilder()
                                    .setType(ReferenceType.PlayerSeatId)
                                    .setId(2),
                            ),
                    ),
            )
            .build()

        // 3) MulliganReq for seat 1
        val greMull = StateMapper.buildMulliganReq(msgId++, gameStateId, 1)

        return wrapGre(greGsm, grePrompt, greMull) to msgId
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

    // --- helpers ---

    /** Wrap one or more GRE messages into a MatchServiceToClientMessage. */
    private fun wrapGre(vararg messages: GREToClientMessage): MatchServiceToClientMessage {
        val event = GreToClientEvent.newBuilder()
        for (msg in messages) event.addGreToClientMessages(msg)
        return MatchServiceToClientMessage.newBuilder()
            .setGreToClientEvent(event)
            .build()
    }

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
}
