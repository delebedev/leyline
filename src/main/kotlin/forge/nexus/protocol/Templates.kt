package forge.nexus.protocol

import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Builds protocol message payloads dynamically.
 *
 * All messages are built from scratch using the proto schema — no binary templates.
 */
object Templates {

    /** Room state event — match room with both players. */
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
     * Initial GRE bundle — built dynamically.
     * Seat 1: ConnectResp + DieRollResults + GameState
     * Seat 2: DieRollResults + GameState + ChooseStartingPlayerReq
     */
    fun initialBundle(
        seatId: Int,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        deckMessage: DeckMessage,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart
        val messages = mutableListOf<GREToClientMessage>()

        if (seatId == 1) {
            // ConnectResp with deck + default settings
            messages.add(buildConnectResp(msgId++, seatId, deckMessage))
        }

        // DieRollResults (both seats see this)
        messages.add(buildDieRollResults(msgId++))

        // Full initial GameState
        val pendingCount = if (seatId == 2) 1 else 0 // ChooseStartingPlayerReq follows
        val gsm = StateMapper.buildInitialGameState(matchId, gameStateId, seatId, bridge, pendingCount)
        messages.add(
            GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build(),
        )

        if (seatId == 2) {
            // ChooseStartingPlayerReq
            messages.add(
                GREToClientMessage.newBuilder()
                    .setType(GREMessageType.ChooseStartingPlayerReq_695e)
                    .addSystemSeatIds(seatId)
                    .setMsgId(msgId++)
                    .setGameStateId(gameStateId)
                    .setChooseStartingPlayerReq(
                        ChooseStartingPlayerReq.newBuilder()
                            .setTeamType(TeamType.Individual)
                            .addSystemSeatIds(1).addSystemSeatIds(2),
                    )
                    .build(),
            )
        }

        return wrapGre(*messages.toTypedArray()) to msgId
    }

    /** DealHand for seat 1 (no MulliganReq) — built from game state. */
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

    /** DealHand + MulliganReq bundled for seat 2 — built from game state. */
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

    /** SetSettingsResp — echo client settings back. */
    fun settingsResp(
        seatId: Int,
        msgId: Int,
        gameStateId: Int,
        clientSettings: SettingsMessage?,
    ): Pair<MatchServiceToClientMessage, Int> {
        val resp = SetSettingsResp.newBuilder()
        if (clientSettings != null) resp.setSettings(clientSettings)
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.SetSettingsResp_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setSetSettingsResp(resp)
            .build()
        return wrapGre(gre) to (msgId + 1)
    }

    // --- private helpers ---

    /** DieRollResults — seat 2 wins (rolls higher). */
    private fun buildDieRollResults(msgId: Int): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.DieRollResultsResp_695e)
            .addSystemSeatIds(1).addSystemSeatIds(2)
            .setMsgId(msgId)
            .setDieRollResultsResp(
                DieRollResultsResp.newBuilder()
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(1).setRollValue(2))
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(2).setRollValue(18)),
            )
            .build()

    /** ConnectResp — success + deck + default settings + version info. */
    private fun buildConnectResp(msgId: Int, seatId: Int, deckMessage: DeckMessage): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.ConnectResp_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setConnectResp(
                ConnectResp.newBuilder()
                    .setStatus(ConnectionStatus.Success_aa9e)
                    .setProtoVer(ProtoVersion.PersistentAnnotations)
                    .setSettings(buildDefaultSettings())
                    .setDeckMessage(deckMessage)
                    .setGrpVersion(Version.newBuilder().setMajorVersion(2026).setMinorVersion(56).setBuildVersion(4))
                    .setGreVersion(Version.newBuilder().setMajorVersion(2026).setMinorVersion(56).setBuildVersion(2)),
            )
            .build()

    /** Default stop settings matching the real server's initial configuration. */
    private fun buildDefaultSettings(): SettingsMessage {
        // (StopType, Team status, Opponents status)
        val stopDefs = listOf(
            Triple(StopType.UpkeepStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
            Triple(StopType.DrawStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
            Triple(StopType.PrecombatMainPhase, SettingStatus.Set, SettingStatus.Clear_a3fe),
            Triple(StopType.BeginCombatStep, SettingStatus.Set, SettingStatus.Set),
            Triple(StopType.DeclareAttackersStep, SettingStatus.Set, SettingStatus.Set),
            Triple(StopType.DeclareBlockersStep, SettingStatus.Set, SettingStatus.Set),
            Triple(StopType.CombatDamageStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
            Triple(StopType.EndCombatStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
            Triple(StopType.PostcombatMainPhase, SettingStatus.Set, SettingStatus.Clear_a3fe),
            Triple(StopType.EndStep_ad1f, SettingStatus.Clear_a3fe, SettingStatus.Set),
            Triple(StopType.FirstStrikeDamageStep, SettingStatus.Set, SettingStatus.Set),
        )
        val builder = SettingsMessage.newBuilder()
        for ((type, teamStatus, oppStatus) in stopDefs) {
            builder.addStops(Stop.newBuilder().setStopType(type).setAppliesTo(SettingScope.Team_ac6e).setStatus(teamStatus))
            builder.addStops(Stop.newBuilder().setStopType(type).setAppliesTo(SettingScope.Opponents).setStatus(oppStatus))
            // Transient stops — all Clear
            builder.addTransientStops(Stop.newBuilder().setStopType(type).setAppliesTo(SettingScope.Team_ac6e).setStatus(SettingStatus.Clear_a3fe))
            builder.addTransientStops(Stop.newBuilder().setStopType(type).setAppliesTo(SettingScope.Opponents).setStatus(SettingStatus.Clear_a3fe))
        }
        builder
            .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
            .setGraveyardOrder(OrderingType.OrderArbitraryAlways)
            .setManaSelectionType(ManaSelectionType.Auto_a88a)
            .setDefaultAutoPassOption(AutoPassOption.ResolveMyStackEffects)
            .setSmartStopsSetting(SmartStopsSetting.Enable_a188)
            .setAutoTapStopsSetting(AutoTapStopsSetting.Enable_ac12)
            .setAutoOptionalPaymentCancellationSetting(Setting.Enable_a20a)
            .setStackAutoPassOption(AutoPassOption.Clear_a465)
        return builder.build()
    }

    /** Wrap one or more GRE messages into a MatchServiceToClientMessage. */
    private fun wrapGre(vararg messages: GREToClientMessage): MatchServiceToClientMessage {
        val event = GreToClientEvent.newBuilder()
        for (msg in messages) event.addGreToClientMessages(msg)
        return MatchServiceToClientMessage.newBuilder()
            .setGreToClientEvent(event)
            .build()
    }
}
