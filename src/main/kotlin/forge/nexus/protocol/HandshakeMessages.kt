package forge.nexus.protocol

import forge.nexus.game.GameBridge
import forge.nexus.game.GsmBuilder
import forge.nexus.game.StateMapper
import forge.nexus.game.mapper.ActionMapper
import forge.nexus.game.mapper.PlayerMapper
import forge.nexus.game.mapper.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Pre-game handshake message factory: roomState, initialBundle, dealHand,
 * mulliganReq, settingsResp. Distinct from [forge.nexus.game.StateMapper]
 * which handles in-game state diffs.
 */
object HandshakeMessages {

    /** Room state event — match room with both players. */
    fun roomState(matchId: String, playerId: String): MatchServiceToClientMessage {
        val roomInfo = MatchGameRoomInfo.newBuilder()
            .setGameRoomConfig(buildRoomConfig(matchId, playerId))
            .setStateType(MatchGameRoomStateType.Playing)
            .addPlayers(playerInfo(playerId, "Player", 1, 1))
            .addPlayers(playerInfo("${playerId}_Familiar", "Sparky", 2, 2))

        return wrapRoomState(roomInfo)
    }

    /**
     * Match-completed room state — sent after IntermissionReq to trigger
     * the client's result screen.
     *
     * Per mtga-internals/docs/post-game-protocol.md, the client waits for
     * [MatchGameRoomStateType.MatchCompleted] before showing the result UI.
     */
    fun matchCompleted(matchId: String, winningTeam: Int, playerId: String, reason: ResultReason = ResultReason.Concede): MatchServiceToClientMessage {
        val result = FinalMatchResult.newBuilder()
            .setMatchId(matchId)
            .setMatchCompletedReason(MatchCompletedReasonType.Success_a26d)
            .addResultList(
                ResultSpec.newBuilder()
                    .setScope(MatchScope.Game_a146)
                    .setResult(ResultType.WinLoss)
                    .setWinningTeamId(winningTeam)
                    .setReason(reason),
            )
            .addResultList(
                ResultSpec.newBuilder()
                    .setScope(MatchScope.Match)
                    .setResult(ResultType.WinLoss)
                    .setWinningTeamId(winningTeam)
                    .setReason(reason),
            )

        val roomInfo = MatchGameRoomInfo.newBuilder()
            .setGameRoomConfig(buildRoomConfig(matchId, playerId))
            .setStateType(MatchGameRoomStateType.MatchCompleted)
            .setFinalMatchResult(result)

        return wrapRoomState(roomInfo)
    }

    // --- Room state helpers ---
    // TODO: store the MatchGameRoomConfig built during roomState() on MatchSession,
    // then pass it into matchCompleted() — single source of truth. Currently both
    // reconstruct from scratch (fine while data is static: matchId, playerId,
    // "AIBotMatch"). Matters when we have real player names, event types, deck IDs.

    private fun playerInfo(userId: String, name: String, seat: Int, team: Int) =
        MatchGameRoomPlayerInfo.newBuilder()
            .setUserId(userId).setPlayerName(name)
            .setSystemSeatId(seat).setTeamId(team)

    private fun buildRoomConfig(matchId: String, playerId: String): MatchGameRoomConfig.Builder {
        val familiarId = "${playerId}_Familiar"
        return MatchGameRoomConfig.newBuilder()
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
    }

    private fun wrapRoomState(roomInfo: MatchGameRoomInfo.Builder): MatchServiceToClientMessage =
        MatchServiceToClientMessage.newBuilder()
            .setMatchGameRoomStateChangedEvent(
                MatchGameRoomStateChangedEvent.newBuilder().setGameRoomInfo(roomInfo),
            )
            .build()

    /**
     * Initial GRE bundle — built dynamically.
     * Seat 1: ConnectResp + DieRollResults + GameState
     * Seat 2: DieRollResults + GameState + ChooseStartingPlayerReq
     *
     * @param dieRollWinner which seat wins the die roll (1 or 2, default 2)
     */
    fun initialBundle(
        seatId: Int,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        deckMessage: DeckMessage,
        bridge: GameBridge,
        dieRollWinner: Int = 2,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart
        val messages = mutableListOf<GREToClientMessage>()

        if (seatId == 1) {
            // ConnectResp with deck + default settings
            messages.add(buildConnectResp(msgId++, seatId, deckMessage))
        }

        // DieRollResults (both seats see this)
        messages.add(buildDieRollResults(msgId++, dieRollWinner))

        // Full initial GameState
        val pendingCount = if (seatId == 2) 1 else 0 // ChooseStartingPlayerReq follows
        val gsm = GsmBuilder.buildInitialGameState(matchId, gameStateId, seatId, bridge, pendingCount)
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
                            .addSystemSeatIds(2).addSystemSeatIds(1),
                    )
                    .build(),
            )
        }

        return wrapGre(*messages.toTypedArray()) to msgId
    }

    /** DealHand for seat 1 (no MulliganReq) — built from game state. */
    /** DealHand only (no MulliganReq) for the given seat. */
    fun dealHand(
        msgId: Int,
        gameStateId: Int,
        bridge: GameBridge,
        seatId: Int,
        diffDeletedInstanceIds: List<Int> = emptyList(),
    ): Pair<MatchServiceToClientMessage, Int> {
        val gsm = GsmBuilder.buildDealHand(bridge, gameStateId, seatId, diffDeletedInstanceIds)
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(seatId)
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
        val gsm = GsmBuilder.buildDealHand(bridge, gameStateId, 2)
            .toBuilder().setPendingMessageCount(1).build()
        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(2)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setGameStateMessage(gsm)
            .build()
        val greMull = GsmBuilder.buildMulliganReq(msgId++, gameStateId, 2)
        return wrapGre(greGsm, greMull) to msgId
    }

    /**
     * MulliganReq sequence for seat 1: GameState(decision=1) + PromptReq + MulliganReq.
     */
    fun mulliganReqSeat1(
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
        mulliganCount: Int = 0,
        numCards: Int = 7,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart

        // 1) Thin GSM Diff: seat 2 no longer pending, decisionPlayer=1
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(
                PlayerMapper.buildPlayerInfo(bridge.getPlayer(2), 2),
            )
            .setTurnInfo(
                TurnInfo.newBuilder().setActivePlayer(2).setDecisionPlayer(1),
            )
            .setPendingMessageCount(2)
            .setPrevGameStateId(gameStateId - 1)
            .addAllTimers(PlayerMapper.buildTimers())
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
            .addSystemSeatIds(2).addSystemSeatIds(1)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER)
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
        val greMull = GsmBuilder.buildMulliganReq(msgId++, gameStateId, 1, numCards = numCards, mulliganCount = mulliganCount)

        return wrapGre(greGsm, grePrompt, greMull) to msgId
    }

    /**
     * GroupReq bundle for London mulligan tuck: GSM Diff + PromptReq + GroupReq.
     *
     * Real server sends a thin GSM (player with mulliganCount, actions, pendingMessageCount=2),
     * a PromptReq (who goes first notification), and the GroupReq.
     */
    fun groupReqBundle(
        msgIdStart: Int,
        gameStateId: Int,
        seatId: Int,
        mulliganCount: Int,
        handInstanceIds: List<Int>,
        cardsToTuck: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart

        // 1) Thin GSM Diff: player with mulliganCount + hand actions
        val game = bridge.getGame()!!
        val actions = ActionMapper.buildActions(game, seatId, bridge)
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(
                PlayerMapper.buildPlayerInfo(bridge.getPlayer(1), 1).toBuilder()
                    .setMulliganCount(mulliganCount)
                    .build(),
            )
            .setPendingMessageCount(2)
            .setPrevGameStateId(gameStateId - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)
        for (action in actions.actionsList) {
            gsm.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setGameStateMessage(gsm)
            .build()

        // 2) PromptReq: who's going first (same as mulliganReqSeat1)
        val dieRollWinner = bridge.playtestConfig.game.dieRollWinner
        val grePrompt = GREToClientMessage.newBuilder()
            .setType(GREMessageType.PromptReq)
            .addSystemSeatIds(seatId).addSystemSeatIds(if (dieRollWinner == seatId) seatId else 3 - seatId)
            .setMsgId(msgId++)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("PlayerId")
                            .setType(ParameterType.Reference_a14a)
                            .setReference(
                                Reference.newBuilder()
                                    .setType(ReferenceType.PlayerSeatId)
                                    .setId(dieRollWinner),
                            ),
                    ),
            )
            .build()

        // 3) GroupReq
        val greGroup = GsmBuilder.buildGroupReq(msgId++, gameStateId, seatId, handInstanceIds, cardsToTuck)

        return wrapGre(greGsm, grePrompt, greGroup) to msgId
    }

    /**
     * Puzzle initial bundle — ConnectResp + Full GSM with stage=Play and
     * all zones populated from the live game state. No DieRoll, no mulligan.
     *
     * pendingMessageCount=1 because ActionsAvailableReq follows immediately.
     */
    fun puzzleInitialBundle(
        seatId: Int,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        var msgId = msgIdStart
        val messages = mutableListOf<GREToClientMessage>()

        if (seatId == 1) {
            // ConnectResp with empty deck (puzzle doesn't use deck message)
            val emptyDeck = GsmBuilder.buildDeckMessage(emptyList())
            messages.add(buildConnectResp(msgId++, seatId, emptyDeck))
        }

        // Full GSM built from live game state (stage=Play, cards in zones)
        val gsm = StateMapper.buildFromGame(
            game = bridge.getGame()!!,
            gameStateId = gameStateId,
            matchId = matchId,
            bridge = bridge,
            viewingSeatId = seatId,
        ).toBuilder()
            .setPendingMessageCount(1) // ActionsAvailableReq follows
            .build()

        messages.add(
            GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build(),
        )

        return wrapGre(*messages.toTypedArray()) to msgId
    }

    /**
     * Puzzle ActionsAvailableReq — first priority stop actions for the puzzle.
     * Follows the Full GSM in the puzzle initial bundle.
     */
    fun puzzleActionsReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        bridge: GameBridge,
    ): Pair<MatchServiceToClientMessage, Int> {
        val game = bridge.getGame()!!
        val actions = ActionMapper.buildActions(game, seatId, bridge)
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setActionsAvailableReq(actions)
            .build()
        return wrapGre(gre) to (msgId + 1)
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

    /** DieRollResults — [winner] seat rolls higher, random d20 values.
     *  Uses [forge.util.MyRandom] so a seeded game produces deterministic rolls. */
    private fun buildDieRollResults(msgId: Int, winner: Int = 2): GREToClientMessage {
        // Generate random d20 values; ensure winner > loser (re-roll on tie).
        // MyRandom.getRandom() respects the seed set in GameBridge.start().
        val rng = forge.util.MyRandom.getRandom()
        var high: Int
        var low: Int
        do {
            high = rng.nextInt(20) + 1
            low = rng.nextInt(20) + 1
        } while (high <= low)
        val seat1Roll = if (winner == 1) high else low
        val seat2Roll = if (winner == 2) high else low
        return GREToClientMessage.newBuilder()
            .setType(GREMessageType.DieRollResultsResp_695e)
            .addSystemSeatIds(winner).addSystemSeatIds(if (winner == 1) 2 else 1)
            .setMsgId(msgId)
            .setDieRollResultsResp(
                DieRollResultsResp.newBuilder()
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(1).setRollValue(seat1Roll))
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(2).setRollValue(seat2Roll)),
            )
            .build()
    }

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
                    .setGrpVersion(Version.newBuilder().setMajorVersion(56).setMinorVersion(10).setBuildVersion(1))
                    .setGreVersion(Version.newBuilder().setMajorVersion(56).setMinorVersion(10).setBuildVersion(1)),
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
