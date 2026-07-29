package leyline.match

import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.OptionalActionPrompt
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.GsmFrame
import leyline.game.bundle.PendingPromptPlan
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles "you may" trigger decisions via OptionalActionMessage (GRE type 45).
 *
 * Lifecycle (mirrors [CombatHandler]'s damage assignment pattern):
 * 1. Engine thread calls `PlayerController.confirmTrigger` → sets
 *    `pendingOptionalAction` → blocks on CompletableFuture
 * 2. Auto-pass loop calls [checkPendingOptionalAction] → detects non-null →
 *    sends OptionalActionMessage to client → returns true (loop exits)
 * 3. Client responds with OptionalResp (AllowYes / CancelNo)
 * 4. [MatchHandler] dispatches to [onOptionalActionResp] → completes future →
 *    engine unblocks → ability resolves or is deleted
 */
class OptionalActionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Check for a pending optional action decision. Called from auto-pass loop
     * after damage assignment check.
     *
     * @return true if an OptionalActionMessage was sent (caller should exit loop)
     */
    fun checkPendingOptionalAction(): Boolean {
        val prompt = ctx.bridge.pendingOptionalAction() ?: return false

        log.info(
            "OptionalActionHandler: optional trigger pending for {}",
            prompt.hostCardName ?: "unknown",
        )
        sendOptionalActionMessage(prompt)
        return true
    }

    /**
     * Handle OptionalActionResp from client.
     */
    fun onOptionalActionResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val prompt =
            bridge.pendingOptionalAction() ?: run {
                log.warn("OptionalActionHandler: no pending prompt for OptionalActionResp")
                return
            }

        val resp = greMsg.optionalResp
        val accepted = resp.response == OptionResponse.AllowYes

        log.info(
            "OptionalActionHandler: {} responded {} for {}",
            if (accepted) "Accept" else "Decline",
            resp.response,
            prompt.hostCardName ?: "unknown",
        )

        val commanderReturn = prompt.commanderReturn
        if (commanderReturn != null) {
            bridge.retireToLimbo(InstanceId(commanderReturn.promptInstanceId))
        }
        bridge.prioritySignal.markPromptResolved()
        bridge.submitOptionalAction(accepted)
        ctx.engine.awaitActionPriority()
        if (commanderReturn != null) {
            sendCommanderPromptCleanup(commanderReturn)
            sink.sendRealGameState(bridge)
        }
        autoPass()
    }

    // --- Private ---

    private fun sendOptionalActionMessage(prompt: OptionalActionPrompt) {
        val bridge = ctx.bridge
        val hostCardId = prompt.hostCardId
        if (hostCardId == null) {
            log.warn("OptionalActionHandler: hostCard is null — cannot send OptionalActionMessage")
            bridge.submitOptionalAction(true) // auto-accept to avoid engine deadlock
            return
        }

        val commanderReturn = prompt.commanderReturn
        val isCommanderReturnPrompt = commanderReturn != null
        val sourceId = commanderReturn?.oldInstanceId ?: bridge.getOrAllocInstanceId(hostCardId).value
        val recipientId = commanderReturn?.promptInstanceId ?: sourceId
        val optionalSourceId = if (isCommanderReturnPrompt) recipientId else sourceId

        // For mid-resolution prompts (e.g. Madness: the card moves Hand→Exile via
        // replacement BEFORE the engine asks "cast for madness?"), force a full
        // state snapshot so the client sees the post-replacement zones before the
        // prompt arrives. Without this the client renders the prompt while the
        // card is still in hand.
        if (prompt.forceSnapshotBeforePrompt) {
            sink.sendRealGameState(bridge)
        }

        val optionalMsgBuilder =
            OptionalActionMessage
                .newBuilder()
                .setSourceId(optionalSourceId)
        if (isCommanderReturnPrompt) {
            optionalMsgBuilder
                .addOptionalActionTypes(CardMechanicType.ZoneTransfer_a57f)
                .addRecipientIds(recipientId)
        }
        val optionalMsg = optionalMsgBuilder.build()

        // TODO: shock land ETB needs promptId 2233 + ReplacementEffect pAnn with
        // allocated affectorId as sourceId. Currently uses generic prompt for all,
        // unless overridden via prompt.customPromptId (e.g. Endure → ENDURE_PUT_COUNTERS).
        val promptBuilder =
            Prompt
                .newBuilder()
                .setPromptId(prompt.customPromptId ?: PromptIds.OPTIONAL_ACTION)
        if (isCommanderReturnPrompt) {
            promptBuilder.addParameters(
                PromptParameter
                    .newBuilder()
                    .setParameterName("CardId")
                    .setType(ParameterType.Number)
                    .setNumberValue(0),
            )
        }
        val promptProto =
            promptBuilder
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(recipientId),
                ).build()

        if (!isCommanderReturnPrompt) {
            sink.sendBundledGRE(
                PendingPromptPlan.build(
                    counters.counter,
                    counters.seatId,
                    GREMessageType.OptionalActionMessage_695e,
                ) {
                    it.optionalActionMessage = optionalMsg
                    it.prompt = promptProto
                    // Controls Cancel button visibility, NOT whether declining is allowed.
                    // Player can always decline via CancelNo response regardless of this value.
                    it.allowCancel = AllowCancel.No_a526
                },
            )
            return
        }
        val commanderContext = checkNotNull(commanderReturn)

        val link = counters.counter.nextGameStateLink()
        val pendingGsmBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setPendingMessageCount(1)
        val snap = ctx.snapshot().withFrameIdentity("", link.gsId)
        pendingGsmBuilder
            .setTurnInfo(GsmFrame.from(snap).turnInfo())
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.Send)
        addReplacementPromptContext(pendingGsmBuilder, snap, hostCardId.value, commanderContext)
        val actions = ActionMapper.buildFromSnapshot(counters.seatId.value, snap, bridge)
        for (action in actions.actionsList) {
            pendingGsmBuilder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(counters.seatId.value)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
        val pendingGsm = pendingGsmBuilder.build()

        val gsmGre =
            sink.makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counters.counter.nextMsgId()) {
                it.gameStateMessage = pendingGsm
            }

        val optionalGre =
            sink.makeGRE(GREMessageType.OptionalActionMessage_695e, link.gsId, counters.counter.nextMsgId()) {
                it.optionalActionMessage = optionalMsg
                it.prompt = promptProto
                // Controls Cancel button visibility, NOT whether declining is allowed.
                // Player can always decline via CancelNo response regardless of this value.
                it.allowCancel = AllowCancel.No_a526
            }

        sink.sendBundledGRE(listOf(gsmGre, optionalGre))
    }

    private fun addReplacementPromptContext(
        builder: GameStateMessage.Builder,
        snap: GsmSnapshot,
        forgeCardId: Int,
        context: CommanderReturnPromptContext,
    ) {
        val cardId = ForgeCardId(forgeCardId)
        val bound = snap.boundCards[cardId] ?: return
        val ownerSeat = bound.snapshot.owner.value

        fun zoneWithContents(
            zoneId: Int,
            extraIds: List<Int> = emptyList(),
            dropId: Int? = null,
        ): ZoneInfo {
            val zone = snap.zones[zoneId]
            val contents =
                zone
                    ?.contents
                    ?.map { ctx.bridge.getOrAllocInstanceId(it).value }
                    ?.filter { it != dropId }
                    .orEmpty() + extraIds
            return ZoneInfo
                .newBuilder()
                .setZoneId(zoneId)
                .setType(zone?.type ?: zoneTypeFor(zoneId))
                .setVisibility(zone?.visibility ?: Visibility.Public)
                .apply { zone?.owner?.let { setOwnerSeatId(it.value) } }
                .addAllObjectInstanceIds(contents.distinct())
                .build()
        }

        builder
            .addZones(zoneWithContents(originZoneId(context), dropId = context.oldInstanceId))
            .addZones(zoneWithContents(destinationZoneId(context), extraIds = listOf(context.promptInstanceId)))
            .addGameObjects(
                ObjectMapper.buildFromSnapshot(
                    bound.snapshot,
                    context.promptInstanceId,
                    destinationZoneId(context),
                    ownerSeat,
                    ctx.bridge.cardProto,
                    Visibility.Public,
                    parentLinkage = bound.parentLinkage,
                ),
            ).addAnnotations(
                AnnotationBuilder
                    .objectIdChanged(
                        InstanceId(context.oldInstanceId),
                        InstanceId(context.promptInstanceId),
                    ).toBuilder()
                    .setId(ctx.bridge.nextAnnotationId())
                    .build(),
            ).addAnnotations(
                AnnotationBuilder
                    .zoneTransfer(
                        InstanceId(context.promptInstanceId),
                        originZoneId(context),
                        destinationZoneId(context),
                        context.transferCategory,
                    ).toBuilder()
                    .setId(ctx.bridge.nextAnnotationId())
                    .build(),
            )
    }

    private fun sendCommanderPromptCleanup(context: CommanderReturnPromptContext) {
        val bridge = ctx.bridge
        val link = counters.counter.nextGameStateLink()
        val snap = ctx.snapshot().withFrameIdentity("", link.gsId)
        val destinationZoneId = destinationZoneId(context)
        val destinationZone = snap.zones[destinationZoneId]
        val zoneInfo =
            ZoneInfo
                .newBuilder()
                .setZoneId(destinationZoneId)
                .setType(destinationZone?.type ?: zoneTypeFor(destinationZoneId))
                .setVisibility(destinationZone?.visibility ?: Visibility.Public)
                .apply { destinationZone?.owner?.let { setOwnerSeatId(it.value) } }
                .addAllObjectInstanceIds(
                    destinationZone
                        ?.contents
                        ?.map { bridge.getOrAllocInstanceId(it).value }
                        .orEmpty(),
                ).build()

        val cleanupGsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setUpdate(GameStateUpdate.Send)
                .addDiffDeletedInstanceIds(context.promptInstanceId)
                .addZones(zoneInfo)
                .build()

        val cleanupGre =
            sink.makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counters.counter.nextMsgId()) {
                it.gameStateMessage = cleanupGsm
            }
        sink.sendBundledGRE(listOf(cleanupGre))
    }

    private fun zoneTypeFor(zoneId: Int): ZoneType =
        when (zoneId) {
            ZoneIds.BATTLEFIELD -> ZoneType.Battlefield
            ZoneIds.EXILE -> ZoneType.Exile
            ZoneIds.COMMAND -> ZoneType.Command
            ZoneIds.P1_HAND, ZoneIds.P2_HAND -> ZoneType.Hand
            ZoneIds.P1_LIBRARY, ZoneIds.P2_LIBRARY -> ZoneType.Library
            ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> ZoneType.Graveyard
            else -> ZoneType.Limbo
        }

    private fun originZoneId(context: CommanderReturnPromptContext): Int = protocolZoneId(context.originZone, context.ownerSeatId)

    private fun destinationZoneId(context: CommanderReturnPromptContext): Int = protocolZoneId(context.destinationZone, context.ownerSeatId)

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun protocolZoneId(
        zone: String,
        ownerSeatId: Int,
    ): Int =
        when (zone) {
            "Battlefield" -> ZoneIds.BATTLEFIELD
            "Graveyard" -> ZoneIds.graveyardOf(ownerSeatId)
            "Exile" -> ZoneIds.EXILE
            "Hand" -> ZoneIds.handOf(ownerSeatId)
            "Library" -> ZoneIds.libraryOf(ownerSeatId)
            "Command" -> ZoneIds.COMMAND
            else -> ZoneIds.LIMBO
        }
}
