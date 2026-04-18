package leyline.game

import forge.game.Game
import forge.game.card.Card
import leyline.bridge.ForgeCardId
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.SeatId
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.PromptIds
import leyline.game.mapper.ShouldStopEvaluator
import leyline.game.mapper.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Pure functions that build GRE message bundles for each flow milestone.
 *
 * No side effects, no Netty, no mutable handler state — takes everything as params,
 * returns messages. The shared [MessageCounter] advances atomically on each call.
 *
 * **Ordering invariant:** every method that includes actions calls
 * [StateMapper.buildDiffFromGame] *first*. Diff-building triggers instanceId
 * reallocation for zone transfers — if actions were built before the diff,
 * they'd reference pre-realloc instanceIds and the client couldn't match them.
 *
 * **Update types** (what the client does with each GSM):
 * - [GameStateUpdate.SendAndRecord] — checkpoint; client persists state.
 *   Always precedes [ActionsAvailableReq] at human decision points.
 * - [GameStateUpdate.SendHiFi] — animation-quality intermediate. AI actions,
 *   phase echoes, combat toggles. Client animates but doesn't save.
 * - [GameStateUpdate.Send] — speculative/transient. Targeting, selection
 *   prompts. Client may discard on undo/cancel.
 *
 * **pendingMessageCount:** when 1, tells the client another message follows
 * in the same logical batch (GSM + request pair). Client defers processing
 * until both arrive. Omit for standalone GSMs (AI actions, echoes).
 *
 * Naming: `xxxBundle` → [BundleResult] (multi-message). Standalone helpers
 * ([queuedGameState], [edictalPass]) return single [GREToClientMessage].
 */
@Suppress("LargeClass") // 1116 LOC — coherent unit; split assessed 2026-04-05, marginal leverage
class BundleBuilder(
    private val bridge: GameBridge,
    private val matchId: String,
    val seatId: Int,
) {

    data class BundleResult(
        val messages: List<GREToClientMessage>,
    )

    /**
     * Post-action state bundle:
     *   GRE 1: Diff GameStateMessage with embedded actions (only changed zones/objects)
     *   GRE 2: ActionsAvailableReq
     */
    fun postAction(
        game: Game,
        counter: MessageCounter,
        revealForSeat: Int? = null,
    ): BundleResult {
        val frame = GsmFrame.from(game, bridge)
        val nextGs = counter.nextGsId()
        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val result = StateMapper.buildDiffFromGame(
            game,
            nextGs,
            matchId,
            bridge,
            updateType = updateType,
            viewingSeatId = seatId,
            revealForSeat = revealForSeat,
        )
        val actions = ActionMapper.buildActions(seatId, bridge)

        // PhaseOrStepModified is now emitted event-driven from GameEvent.PhaseChanged
        // in StateMapper Stage 2b — no injection needed here.

        // Re-embed stripped actions into the GSM
        val gs = GsmBuilder.embedActions(result.gsm, actions, frame, recipientSeatId = seatId)

        // QueuedGSM split disabled: the caster always gets regular GameStateMessage.
        @Suppress("UnusedPrivateProperty")
        val split: Triple<GameStateMessage, GameStateMessage, GameStateMessage>? = null

        val messages = if (split != null) {
            val (queued1, queued2, main) = split
            listOf(
                makeGRE(GREMessageType.QueuedGameStateMessage, queued1.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = queued1
                },
                makeGRE(GREMessageType.QueuedGameStateMessage, queued2.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = queued2
                },
                makeGRE(GREMessageType.GameStateMessage_695e, main.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = main
                },
                makeGRE(GREMessageType.ActionsAvailableReq_695e, main.gameStateId, counter.nextMsgId()) {
                    it.actionsAvailableReq = actions
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                },
            )
        } else {
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                },
                makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, counter.nextMsgId()) {
                    it.actionsAvailableReq = actions
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                },
            )
        }

        return BundleResult(messages)
    }

    /**
     * State-only diff: Diff GameStateMessage without ActionsAvailableReq.
     * Used to show intermediate state (e.g. spell on stack) without
     * prompting the client for a response.
     */
    fun stateOnlyDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val result = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)

        // QueuedGSM split disabled (see postAction comment above).
        @Suppress("UnusedPrivateProperty")
        val split: Triple<GameStateMessage, GameStateMessage, GameStateMessage>? = null

        val messages = if (split != null) {
            val (queued1, queued2, main) = split
            listOf(
                makeGRE(GREMessageType.QueuedGameStateMessage, queued1.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = queued1
                },
                makeGRE(GREMessageType.QueuedGameStateMessage, queued2.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = queued2
                },
                makeGRE(GREMessageType.GameStateMessage_695e, main.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = main
                },
            )
        } else {
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = result.gsm
                },
            )
        }

        return BundleResult(messages)
    }

    /**
     * Remote action diff: content GS Diff with SendHiFi, then a bare SendHiFi echo.
     *
     * Client expects a commit-frame echo after remote-seat content GSMs.
     * Both messages are standalone (no pendingMessageCount). The first carries
     * the state delta + naive actions; the second is a bare diff (empty
     * anns/pAnns/objects/zones with prevGsId chained to the content frame)
     * used for animation pacing.
     */
    fun remoteActionDiff(
        game: Game,
        counter: MessageCounter,
        turnStarted: Boolean = false,
    ): BundleResult {
        val frame = GsmFrame.from(game, bridge)
        val nextGs = counter.nextGsId()
        // Build state first (triggers instanceId realloc), then actions with new IDs
        val gsBase = StateMapper.buildDiffFromGame(
            game,
            nextGs,
            matchId,
            bridge,
            updateType = GameStateUpdate.SendHiFi,
            viewingSeatId = seatId,
        ).gsm
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Client expects human's potential actions embedded during AI turn.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        // Inject turn-start annotation when applicable. PhaseOrStepModified is now
        // emitted event-driven in Stage 2b (inside buildDiffFromGame above).
        val gsWithAnnotations = if (turnStarted) {
            gsBase.toBuilder().apply {
                addAnnotations(
                    AnnotationBuilder.newTurnStarted(SeatId(frame.activeSeat))
                        .toBuilder().setId(bridge.nextAnnotationId()).build(),
                )
            }.build()
        } else {
            gsBase
        }

        // Embed actions WITHOUT pendingMessageCount (no follow-up message expected)
        val gs = gsWithAnnotations.toBuilder()
        for (action in actions.actionsList) {
            gs.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }

        val content = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs.build()
        }
        val echo = buildEchoDiffGsm(counter, GameStateUpdate.SendHiFi)

        return BundleResult(listOf(content, echo))
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by [AutoPassEngine] on the session thread to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the **session-side** layer of a two-layer auto-pass system:
     *
     * 1. **Engine-side** — [PlayableActionQuery.hasPlayableNonManaAction] runs
     *    inside [WebPlayerController.chooseSpellAbilityToPlay] on the engine
     *    thread, own-turn only. When false, the engine auto-passes before the
     *    bridge round-trip even happens. The session thread never sees it.
     *
     * 2. **Session-side** (this) — checks the proto action list we already
     *    built. Covers opponent-turn priority and any case the engine-side
     *    skip didn't fire. No redundant Game queries needed.
     *
     * Stateless — lives in [Companion] so callers don't need an instance.
     */

    // --- Request builders (delegate to RequestBuilder) ---
    // MatchSession uses these instead of calling RequestBuilder directly,
    // keeping RequestBuilder as an internal dependency of the bundle layer.

    /** Build playable actions for a seat (with legality checks). */
    fun buildActions(): ActionsAvailableReq =
        ActionMapper.buildActions(seatId, bridge)

    /** Build a [SelectNReq] from a pending "choose cards" prompt. */
    fun buildSelectNReq(
        prompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
    ): SelectNReq = RequestBuilder.buildSelectNReq(prompt, bridge)

    /** Build a [SearchReq] GRE message with populated inner fields for library search. */
    fun buildSearchReq(
        msgId: Int,
        gsId: Int,
        sourceInstanceId: Int,
        libraryZoneId: Int,
        allLibraryIds: List<Int>,
        validTargetIds: List<Int>,
        maxFind: Int = 1,
        allowFailToFind: Boolean = true,
    ): GREToClientMessage {
        val searchReq = SearchReq.newBuilder()
            .setMaxFind(maxFind)
            .addZonesToSearch(libraryZoneId)
            .addAllItemsToSearch(allLibraryIds)
            .addAllItemsSought(validTargetIds)
            .setSourceId(sourceInstanceId)
        if (allowFailToFind) {
            searchReq.setAllowFailToFind(AllowFailToFind.Any)
        }
        return GREToClientMessage.newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(seatId)
            .setPrompt(
                Prompt.newBuilder()
                    .setPromptId(PromptIds.SEARCH)
                    .addParameters(
                        PromptParameter.newBuilder()
                            .setParameterName("CardId")
                            .setType(ParameterType.Number)
                            .setNumberValue(sourceInstanceId),
                    ),
            )
            .setSearchReq(searchReq)
            .build()
    }

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(): DeclareAttackersReq =
        RequestBuilder.buildDeclareAttackersReq(seatId, bridge)

    /**
     * Phase transition bundle matching expected client-facing message pattern (5 messages):
     *   1. GS Diff SendHiFi (2x PhaseOrStepModified, gameInfo, players, actions)
     *   2. GS Diff SendHiFi echo (turnInfo + actions only)
     *   3. GS Diff SendAndRecord (1x PhaseOrStepModified, actions)
     *   4. PromptReq (promptId=37)
     *   5. ActionsAvailableReq (promptId=2)
     */
    fun phaseTransitionDiff(
        game: Game,
        counter: MessageCounter,
    ): BundleResult {
        val prevGs = counter.currentGsId()
        val nextGs = counter.nextGsId()

        val frame = GsmFrame.from(game, bridge)
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Client expects Cast/Play actions embedded regardless of current phase (cosmetic only;
        // actual priority gating uses ActionsAvailableReq sent when human gets priority).
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        // Message 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
        val gs1 = GsmBuilder.buildTransitionState(
            nextGs,
            prevGameStateId = prevGs,
            matchId,
            bridge,
            frame,
            isStageTransition = true,
            actions = actions,
            actionSeatId = seatId,
        )
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs1
        }

        // Message 2: SendHiFi echo (turnInfo + actions, no annotations)
        val msg1GsId = nextGs
        val echoGs = counter.nextGsId()
        val echoBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(echoGs)
            .setPrevGameStateId(msg1GsId)
            .setTurnInfo(frame.turnInfo())
            .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, seatId, pending = false)
        val msg2 = makeGRE(GREMessageType.GameStateMessage_695e, echoGs, counter.nextMsgId()) {
            it.gameStateMessage = echoBuilder.build()
        }

        // Message 3: SendAndRecord with 1x PhaseOrStepModified
        val commitGs = counter.nextGsId()
        val commitBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(commitGs)
            .setPrevGameStateId(echoGs)
            .setTurnInfo(frame.turnInfo())
            .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() })
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, seatId)
        val msg3 = makeGRE(GREMessageType.GameStateMessage_695e, commitGs, counter.nextMsgId()) {
            it.gameStateMessage = commitBuilder.build()
        }

        // Message 4: PromptReq (promptId=37)
        val msg4 = makeGRE(GREMessageType.PromptReq, commitGs, counter.nextMsgId()) {
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
        }

        // Message 5: ActionsAvailableReq (promptId=2)
        val msg5 = makeGRE(GREMessageType.ActionsAvailableReq_695e, commitGs, counter.nextMsgId()) {
            it.actionsAvailableReq = actions
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
        }

        return BundleResult(listOf(msg1, msg2, msg3, msg4, msg5))
    }

    /** Embed stripped-down actions from ActionsAvailableReq into a GSM builder. */
    private fun embedActions(
        builder: GameStateMessage.Builder,
        actions: ActionsAvailableReq,
        seatId: Int,
        pending: Boolean = true,
    ) {
        if (pending) builder.setPendingMessageCount(1)
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo.newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
    }

    /**
     * Echo-back bundle for iterative attacker toggle: thin Diff with provisional
     * combat state on toggled creatures + fresh DeclareAttackersReq.
     *
     * Client expects `objects=1` per toggle with the creature's attack state
     * and tap state reflecting the provisional selection. We synthesize this
     * because the engine's combat object doesn't track provisional toggles.
     *
     * @param selectedAttackerIds instanceIds currently selected as attackers
     * @param allLegalAttackerIds all instanceIds eligible to attack (for deselect detection)
     */
    @Suppress("UnusedParameter")
    fun echoAttackersBundle(
        game: Game,
        counter: MessageCounter,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())

        // Build provisional creature objects for ALL legal attackers.
        // Echo objects carry NO combat state — only base card fields.
        val objects = mutableListOf<GameObjectInfo>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            if (iid !in allLegalAttackerIds) continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    card,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    controllerSeatId = seatId,
                    bridge = bridge,
                ),
            )
        }

        // Cumulative turn-level actions (Cast, Play, ActivateMana, Activate).
        // Client expects echo GSMs to include this running log.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .addAllGameObjects(objects)
            .setPrevGameStateId(nextGs - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gsmBuilder.build()
        }

        val req = RequestBuilder.buildDeclareAttackersReq(
            seatId,
            bridge,
            committedAttackerIds = selectedAttackerIds.toSet(),
        )
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, counter.nextMsgId()) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        counter: MessageCounter,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId).gsm
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val req = prebuiltReq ?: RequestBuilder.buildDeclareAttackersReq(seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, counter.nextMsgId()) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Echo-back for iterative blocker toggle: thin Diff GSM with provisional
     * blocker state on toggled creatures + fresh DeclareBlockersReq.
     *
     * Same pattern as [echoAttackersBundle] — engine's combat object doesn't
     * track provisional blocker selections during iterative declaration.
     */
    fun echoBlockersBundle(
        game: Game,
        counter: MessageCounter,
        blockAssignments: Map<Int, Int>, // blockerInstanceId → attackerInstanceId
    ): BundleResult {
        val nextGs = counter.nextGsId()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())

        // Build provisional creature objects for assigned blockers.
        // Echo objects carry NO combat state — only base card fields.
        val objects = mutableListOf<GameObjectInfo>()
        val blockerSet = blockAssignments.keys
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            if (iid !in blockerSet) continue

            objects.add(
                ObjectMapper.buildProvisionalCombatObject(
                    card,
                    iid,
                    ZoneIds.BATTLEFIELD,
                    ownerSeatId = seatId,
                    controllerSeatId = seatId,
                    bridge = bridge,
                ),
            )
        }

        // Cumulative turn-level actions — same pattern as attacker echo.
        val actions = ActionMapper.buildNaiveActions(seatId, bridge)

        val gsmBuilder = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(nextGs)
            .addAllGameObjects(objects)
            .setPrevGameStateId(nextGs - 1)
            .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(gsmBuilder, actions, seatId, pending = false)

        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gsmBuilder.build()
        }

        // Re-prompt with assigned blockers' attackerInstanceIds cleared
        val req = RequestBuilder.buildDeclareBlockersReq(game, seatId, bridge, blockerAssignments = blockAssignments)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, counter.nextMsgId()) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Declare-blockers bundle: Diff (DeclareBlock step) + DeclareBlockersReq (prompt id=7).
     */
    fun declareBlockersBundle(
        game: Game,
        counter: MessageCounter,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val updateType = StateMapper.resolveUpdateType(game, bridge, seatId)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId).gsm
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val req = RequestBuilder.buildDeclareBlockersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, counter.nextMsgId()) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Select-targets bundle: GameState + SelectTargetsReq.
     *
     * Builds the diff **first** (which triggers instanceId reallocs for zone
     * transfers like Hand→Stack), then builds the SelectTargetsReq so that
     * `sourceId` and target instanceIds reflect the post-realloc state.
     * Without this ordering, `sourceId` would reference a retired instanceId
     * and the client wouldn't draw the targeting arrow.
     *
     * Sets `allowCancel=Abort` and `allowUndo=true` on the GRE wrapper
     * (client shows Cancel button and allows undo during targeting).
     */
    fun selectTargetsBundle(
        game: Game,
        counter: MessageCounter,
        prompt: leyline.bridge.InteractivePromptBridge.PendingPrompt,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        // Build diff first — triggers instanceId reallocs for zone transfers
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId).gsm
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        // Build SelectTargetsReq AFTER diff so sourceId uses post-realloc instanceIds
        val req = RequestBuilder.buildSelectTargetsReq(prompt, bridge, seatId)
        val msg2 = makeGRE(GREMessageType.SelectTargetsReq_695e, nextGs, counter.nextMsgId()) {
            it.selectTargetsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * SelectN bundle: GameState + SelectNReq.
     * Used for "choose N cards" prompts (discard, sacrifice, etc.).
     */
    fun selectNBundle(
        game: Game,
        counter: MessageCounter,
        req: SelectNReq,
        isLegendRule: Boolean = false,
        isRevealChoose: Boolean = false,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId).gsm
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.SelectNreq, nextGs, counter.nextMsgId()) {
            it.selectNReq = req
            when {
                isLegendRule -> {
                    // Legend rule: promptId=72 + CardId param, no cancel allowed.
                    it.setPrompt(
                        Prompt.newBuilder()
                            .setPromptId(PromptIds.SELECT_N_LEGEND_RULE)
                            .addParameters(
                                PromptParameter.newBuilder()
                                    .setParameterName("CardId")
                                    .setType(ParameterType.Number),
                            )
                            .build(),
                    )
                    it.allowCancel = AllowCancel.No_a526
                }
                isRevealChoose -> {
                    // Reveal-choose (Duress, Revealing Eye): no cancel.
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build())
                    it.allowCancel = AllowCancel.No_a526
                }
                else -> {
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build())
                }
            }
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * CastingTimeOptions bundle: GameState + CastingTimeOptionsReq.
     * Used for modal ETB/cast prompts (Charming Prince, Goblin Surprise, etc.).
     *
     * Sends a GSM diff first (state may have changed during trigger/resolution),
     * followed by CastingTimeOptionsReq with the ModalReq payload. Sets
     * allowCancel=Abort and allowUndo=true (client shows Cancel button).
     */
    /**
     * @param sourceCardInstanceId instanceId of the source card (for ability parentId).
     *   Null for spell-time modals where the card itself is on the stack.
     * @param sourceCardGrpId grpId of the source card (for ability objectSourceGrpId).
     *   Null for spell-time modals.
     */
    fun castingTimeOptionsBundle(
        game: Game,
        counter: MessageCounter,
        req: CastingTimeOptionsReq,
        sourceCardInstanceId: Int? = null,
        sourceCardGrpId: Int? = null,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gsResult = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId)
        val gsBuilder = gsResult.gsm.toBuilder()
            .setPendingMessageCount(1)

        // Synthesize the ability game object on the stack for ETB modals.
        // Forge adds the trigger to its stack AFTER mode choice (PlaySpellAbility line 733),
        // but the client needs the ability visible in the GSM to render the modal dialog.
        // Only inject when sourceCardInstanceId is set (triggered ability path).
        // Spell-time modals (kicker, spell modals) don't need this.
        if (sourceCardInstanceId != null && req.castingTimeOptionReqCount > 0) {
            val cto = req.getCastingTimeOptionReq(0)
            val abilityIid = cto.affectedId
            val abilityGrpId = cto.grpId
            if (abilityIid > 0 && abilityGrpId > 0) {
                // Only inject if not already present (e.g. spell-time modals where card is on stack)
                val alreadyPresent = gsBuilder.gameObjectsList.any { it.instanceId == abilityIid }
                if (!alreadyPresent) {
                    val abilityBuilder = GameObjectInfo.newBuilder()
                        .setInstanceId(abilityIid)
                        .setGrpId(abilityGrpId)
                        .setType(GameObjectType.Ability)
                        .setZoneId(ZoneIds.STACK)
                        .setVisibility(Visibility.Public)
                        .setOwnerSeatId(seatId)
                        .setControllerSeatId(seatId)
                    if (sourceCardGrpId != null) {
                        abilityBuilder.setObjectSourceGrpId(sourceCardGrpId)
                    } else {
                        abilityBuilder.setObjectSourceGrpId(abilityGrpId)
                    }
                    // sourceCardInstanceId is non-null here — outer `if` on line 694 guarded it.
                    abilityBuilder.setParentId(sourceCardInstanceId)
                    val abilityObj = abilityBuilder.build()
                    gsBuilder.addGameObjects(abilityObj)

                    // Add to stack zone (create if absent in the diff)
                    val stackIdx = gsBuilder.zonesList.indexOfFirst { it.type == ZoneType.Stack }
                    if (stackIdx >= 0) {
                        val updated = gsBuilder.getZones(stackIdx).toBuilder()
                            .addObjectInstanceIds(abilityIid)
                            .build()
                        gsBuilder.setZones(stackIdx, updated)
                    } else {
                        gsBuilder.addZones(
                            ZoneInfo.newBuilder()
                                .setZoneId(ZoneIds.STACK)
                                .setType(ZoneType.Stack)
                                .setVisibility(Visibility.Public)
                                .addObjectInstanceIds(abilityIid)
                                .build(),
                        )
                    }
                }
            }
        }

        val gs = gsBuilder.build()

        // Re-snapshot baseline with the injected ability so the next diff
        // can emit diffDeletedInstanceIds when the ability is no longer present.
        bridge.snapshotDiffBaseline(gs)

        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.CastingTimeOptionsReq_695e, nextGs, counter.nextMsgId()) {
            it.castingTimeOptionsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * PayCosts bundle: GameState + PayCostsReq.
     * Tells the client to show its native mana payment UI.
     *
     * Currently unused — mana payment auto-resolves via the engine's AI
     * mana solver + checkPendingPrompt(). Wire this in when implementing
     * interactive mana payment in the compatibility flow.
     *
     * The client responds with PerformActionResp (already handled).
     */
    fun payCostsBundle(
        game: Game,
        counter: MessageCounter,
        req: PayCostsReq,
    ): BundleResult {
        val nextGs = counter.nextGsId()

        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = GameStateUpdate.Send, viewingSeatId = seatId).gsm
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
            it.gameStateMessage = gs
        }

        val msg2 = makeGRE(GREMessageType.PayCostsReq_695e, nextGs, counter.nextMsgId()) {
            it.payCostsReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PAY_COSTS).build())
        }

        return BundleResult(listOf(msg1, msg2))
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        counter: MessageCounter,
    ): GREToClientMessage = makeGRE(GREMessageType.QueuedGameStateMessage, counter.currentGsId(), counter.nextMsgId()) {
        it.gameStateMessage = gameState
    }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(counter: MessageCounter): BundleResult {
        val edictal = EdictalMessage.newBuilder()
            .setEdictMessage(
                ClientToGREMessage.newBuilder()
                    .setType(ClientMessageType.PerformActionResp_097b)
                    .setSystemSeatId(seatId)
                    .setPerformActionResp(
                        PerformActionResp.newBuilder()
                            .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                    ),
            )
            .build()
        val msg = makeGRE(GREMessageType.EdictalMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
            it.edictalMessage = edictal
        }
        return BundleResult(listOf(msg))
    }

    /**
     * Game-over sequence: 3x GS Diff + IntermissionReq.
     * Pure proto construction — no bridge or game engine access.
     *
     * Protocol pattern:
     * - gs1: GameInfo(stage=GameOver, matchState=GameComplete, 1 result scope=Game),
     *        players with PendingLoss, teams, LossOfGame annotation (if lethal)
     * - gs2: GameInfo(stage=GameOver, matchState=MatchComplete, 2 results Game+Match)
     * - gs3: bare diff with pendingMessageCount=1
     * - IntermissionReq: options, intermissionPrompt(27) with WinningTeamId param
     *
     * @param reason Game_ae0a for natural game end, Concede for concession
     * @param losingPlayerSeatId seat of the losing player (for LossOfGame annotation)
     * @param lossReason wire-level loss reason for the LossOfGame annotation
     */
    fun gameOverBundle(
        winningTeam: Int,
        counter: MessageCounter,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: AnnotationLossReason = AnnotationLossReason.LifeTotal,
    ): BundleResult {
        val prevGsId = counter.currentGsId()
        val losingTeam = if (winningTeam == 1) 2 else 1

        // Shared GameInfo fields matching initial bundle (StateMapper.buildFromGame)
        fun baseGameInfo() = GameInfo.newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.GameOver)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setSuperFormat(SuperFormat.Constructed)
            .setMulliganType(MulliganType.London)
            .setDeckConstraintInfo(
                DeckConstraintInfo.newBuilder()
                    .setMinDeckSize(60).setMaxDeckSize(250).setMaxSideboardSize(15),
            )

        val gameResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Game_a146)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)
            .setReason(reason)

        val matchResult = ResultSpec.newBuilder()
            .setScope(MatchScope.Match)
            .setResult(ResultType.WinLoss)
            .setWinningTeamId(winningTeam)
            .setReason(reason)

        // gs1: GameComplete with Game result only, PendingLoss players
        val gs1Info = baseGameInfo()
            .setMatchState(MatchState.GameComplete)
            .addResults(gameResult)
        val gs1Id = counter.nextGsId()
        val gs1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs1Id)
            .setPrevGameStateId(prevGsId)
            .setGameInfo(gs1Info).setUpdate(GameStateUpdate.SendAndRecord)
        // Teams with PendingLoss for losing team
        gs1.addTeams(TeamInfo.newBuilder().setId(losingTeam).addPlayerIds(losingPlayerSeatId).setStatus(TeamStatus.PendingLoss_a458))
        // Players: loser with full state (lifeTotal, maxHandSize, etc.) + PendingLoss status
        val game = bridge.getGame()
        if (game != null) {
            val gameOverSnap = GsmSnapshot.capture(game, bridge, matchId)
            val loserInfo = PlayerMapper.buildFromSnapshot(gameOverSnap, losingPlayerSeatId).toBuilder()
                .setStatus(PlayerStatus.PendingLoss_a1c6)
            gs1.addPlayers(loserInfo)
        }
        // Timers — inactivity timer on gs1
        gs1.addAllTimers(PlayerMapper.buildTimers())
        // LossOfGame annotation
        if (losingPlayerSeatId != 0) {
            gs1.addAnnotations(AnnotationBuilder.lossOfGame(SeatId(losingPlayerSeatId), lossReason))
        }

        // gs2: MatchComplete with both Game + Match results
        val gs2Info = baseGameInfo()
            .setMatchState(MatchState.MatchComplete)
            .addResults(gameResult)
            .addResults(matchResult)
        val gs2Id = counter.nextGsId()
        val gs2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs2Id)
            .setPrevGameStateId(gs1Id)
            .setGameInfo(gs2Info).setUpdate(GameStateUpdate.SendAndRecord)

        // gs3: bare diff with pendingMessageCount=1 (IntermissionReq follows)
        val gs3Id = counter.nextGsId()
        val gs3 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).setGameStateId(gs3Id)
            .setPrevGameStateId(gs2Id)
            .setPendingMessageCount(1)
            .setUpdate(GameStateUpdate.SendAndRecord)

        val messages = mutableListOf(
            makeGRE(GREMessageType.GameStateMessage_695e, gs1Id, counter.nextMsgId()) { it.gameStateMessage = gs1.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gs2Id, counter.nextMsgId()) { it.gameStateMessage = gs2.build() },
            makeGRE(GREMessageType.GameStateMessage_695e, gs3Id, counter.nextMsgId()) { it.gameStateMessage = gs3.build() },
        )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gs3Id, counter.nextMsgId()) {
                it.intermissionReq = IntermissionReq.newBuilder()
                    .setResult(
                        ResultSpec.newBuilder()
                            .setScope(MatchScope.Match)
                            .setResult(ResultType.WinLoss)
                            .setWinningTeamId(winningTeam)
                            .setReason(reason),
                    )
                    .addOptions(
                        UserOption.newBuilder()
                            .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.DRAW_CARD))
                            .setResponseType(ClientMessageType.DrawCardResp),
                    )
                    .addOptions(
                        UserOption.newBuilder()
                            .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.REVEAL_HAND))
                            .setResponseType(ClientMessageType.RevealHandResp),
                    )
                    .setIntermissionPrompt(
                        Prompt.newBuilder()
                            .setPromptId(PromptIds.MATCH_RESULT_WIN_LOSS)
                            .addParameters(
                                PromptParameter.newBuilder()
                                    .setParameterName("WinningTeamId")
                                    .setType(ParameterType.Number)
                                    .setNumberValue(winningTeam),
                            ),
                    )
                    .build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * Timer start: sends [TimerStateMessage] (GRE type 56) with Decision timer running.
     * The real server sends this on priority grant — client shows rope countdown.
     */
    fun timerStart(counter: MessageCounter, durationSec: Int = 30): BundleResult =
        buildTimerBundle(counter, running = true, durationSec = durationSec)

    /**
     * Timer stop: sends [TimerStateMessage] with running=false.
     * Sent when client responds to an action (pass/cast/play).
     */
    fun timerStop(counter: MessageCounter, durationSec: Int = 30): BundleResult =
        buildTimerBundle(counter, running = false, durationSec = durationSec)

    private fun buildTimerBundle(counter: MessageCounter, running: Boolean, durationSec: Int): BundleResult {
        val timer = TimerStateMessage.newBuilder()
            .setSeatId(seatId)
            .addTimers(
                TimerInfo.newBuilder()
                    .setTimerId(1)
                    .setType(TimerType.Decision)
                    .setDurationSec(durationSec)
                    .setElapsedSec(0)
                    .setRunning(running)
                    .setBehavior(TimerBehavior.Timeout_a3cd),
            )
            .build()
        val msg = makeGRE(GREMessageType.TimerStateMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
            it.timerStateMessage = timer
        }
        return BundleResult(listOf(msg))
    }

    /**
     * Build a [ModalReq] + [CastingTimeOptionsReq] proto for a modal prompt.
     *
     * Pure proto construction — caller handles card lookup, fallback, and pending state.
     *
     * @param parentGrpId the abilityGrpId of the modal ability
     * @param childGrpIds the grpIds for each modal option
     * @param minSel minimum number of modes to select
     * @param maxSel maximum number of modes to select
     * @param sourceInstanceId the instanceId for affectedId/affectorId
     * @param grpId the grpId for the CTO entry (card grpId for spells, ability grpId for triggers)
     * @param ctoId CTO identifier (1-2 for spell-time, 3 for triggered abilities)
     * @param playerIdToPrompt seat number to prompt (null omits the field)
     */
    fun buildModalCastingTimeOptionsReq(
        parentGrpId: Int,
        childGrpIds: List<Int>,
        minSel: Int,
        maxSel: Int,
        sourceInstanceId: Int,
        grpId: Int,
        ctoId: Int = 2,
        playerIdToPrompt: Int? = null,
    ): CastingTimeOptionsReq {
        val modalReq = ModalReq.newBuilder()
            .setAbilityGrpId(parentGrpId)
            .setMinSel(minSel)
            .setMaxSel(maxSel)
        for (childGrpId in childGrpIds) {
            modalReq.addModalOptions(ModalOption.newBuilder().setGrpId(childGrpId))
        }
        val ctoBuilder = CastingTimeOptionReq.newBuilder()
            .setCtoId(ctoId)
            .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
            .setAffectedId(sourceInstanceId)
            .setAffectorId(sourceInstanceId)
            .setGrpId(grpId)
            .setIsRequired(true)
            .setModalReq(modalReq)
        if (playerIdToPrompt != null) {
            ctoBuilder.setPlayerIdToPrompt(playerIdToPrompt)
        }
        return CastingTimeOptionsReq.newBuilder()
            .addCastingTimeOptionReq(ctoBuilder)
            .build()
    }

    /**
     * Build a [CastingTimeOptionsReq] for optional costs (kicker, buyback, etc.).
     *
     * Pure proto construction — caller handles SpellAbility lookup and pending state.
     *
     * @param instanceId the instanceId of the card being cast
     * @param optionalCosts list of (ctoType, abilityGrpId) for each optional cost
     * @return pair of (CastingTimeOptionsReq, costCtoIds)
     */
    fun buildOptionalCostCastingTimeOptionsReq(
        instanceId: Int,
        optionalCosts: List<Pair<CastingTimeOptionType, Int>>,
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val ctoReqBuilder = CastingTimeOptionsReq.newBuilder()
        val costCtoIds = mutableListOf<Int>()
        for ((i, cost) in optionalCosts.withIndex()) {
            val ctoId = i + 1
            costCtoIds.add(ctoId)
            ctoReqBuilder.addCastingTimeOptionReq(
                CastingTimeOptionReq.newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(cost.first)
                    .setAffectedId(instanceId)
                    .setAffectorId(instanceId)
                    .setGrpId(cost.second),
            )
        }
        ctoReqBuilder.addCastingTimeOptionReq(
            CastingTimeOptionReq.newBuilder()
                .setCtoId(0)
                .setCastingTimeOptionType(CastingTimeOptionType.Done)
                .setIsRequired(true),
        )
        return Pair(ctoReqBuilder.build(), costCtoIds)
    }

    /**
     * Build a bare echo diff GSM (empty Diff with just gsId chain + update type).
     * Used for select-targets echo-back before re-prompt and for remote-action
     * animation commit frames.
     */
    fun buildEchoDiffGsm(
        counter: MessageCounter,
        updateType: GameStateUpdate = GameStateUpdate.Send,
    ): GREToClientMessage {
        val gsId = counter.nextGsId()
        return makeGRE(GREMessageType.GameStateMessage_695e, gsId, counter.nextMsgId()) {
            it.gameStateMessage = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gsId)
                .setPrevGameStateId(gsId - 1)
                .setUpdate(updateType)
                .build()
        }
    }

    /**
     * Resolve candidateRefs to Forge cards and build a surveil/scry bundle.
     *
     * Encapsulates card resolution (candidateRefs → Forge Card + instanceId) plus
     * bundle building (reveal diff + GroupReq) so callers don't need to do inline
     * card resolution. Returns null if no cards could be resolved from candidateRefs.
     *
     * @param candidateRefs prompt candidate references from [InteractivePromptBridge]
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun resolveSurveilScryBundle(
        candidateRefs: List<PromptCandidateRefDto>,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult? {
        val game = bridge.getGame() ?: return null
        val resolved = candidateRefs
            .filter { it.kind == "card" }
            .mapNotNull { ref ->
                val card = game.findById(ref.entityId)
                if (card != null) card to bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value else null
            }
        if (resolved.isEmpty()) return null
        val topCards = resolved.map { it.first }
        val cardInstanceIds = resolved.map { it.second }
        val sourceId = game.stack.firstOrNull()?.let { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value } ?: 0
        return surveilScryBundle(topCards, cardInstanceIds, sourceId, context, counter)
    }

    /**
     * Surveil/scry bundle: reveal diff (card objects with Private visibility) + GroupReq.
     *
     * Builds a GSM diff that exposes library top card(s) as `visibility=Private, viewers=[seatId]`
     * so the client shows them face-up in the surveil/scry modal, followed by a GroupReq.
     *
     * @param topCards the cards being surveilled/scryed
     * @param cardInstanceIds instanceIds corresponding to [topCards]
     * @param sourceId instanceId of the triggering spell
     * @param context whether this is surveil or scry
     * @param counter message counter for sequencing
     */
    fun surveilScryBundle(
        topCards: List<Card>,
        cardInstanceIds: List<Int>,
        sourceId: Int,
        context: GroupingContext,
        counter: MessageCounter,
    ): BundleResult {
        val libZoneId = if (seatId == 1) ZoneIds.P1_LIBRARY else ZoneIds.P2_LIBRARY
        val revealedObjects = topCards.map { card ->
            ObjectMapper.buildCardObject(card, bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value, libZoneId, seatId, bridge, Visibility.Private)
                .toBuilder().addViewers(seatId).build()
        }
        val gsId = counter.nextGsId()
        val revealDiff = makeGRE(GREMessageType.GameStateMessage_695e, gsId, counter.nextMsgId()) {
            it.gameStateMessage = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gsId)
                .setPrevGameStateId(gsId - 1)
                .addAllGameObjects(revealedObjects)
                .build()
        }

        val groupReq = GsmBuilder.buildSurveilScryGroupReq(
            msgId = counter.nextMsgId(),
            gameStateId = gsId,
            seatId = seatId,
            cardInstanceIds = cardInstanceIds,
            context = context,
            sourceInstanceId = sourceId,
        )
        return BundleResult(listOf(revealDiff, groupReq))
    }

    /** Build a single GRE message. */
    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    companion object {
        /**
         * Pure function — no instance state needed. Checks if the only action
         * available is Pass (no Cast, Play, Activate).
         */
        fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
            actions.actionsList.all { !ShouldStopEvaluator.shouldStop(it.actionType) }
    }
}
