package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationFrameFinalizer
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionAcknowledgements
import leyline.game.state.ProjectionOutput
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Finalizes one viewer's state projection as one tentative value transition. */
object StateProjectionCompiler {
    data class Result(
        val gsm: GameStateMessage,
        val projectionSnapshot: GsmSnapshot,
        val output: ProjectionOutput,
        val transition: ProjectionTransition,
        val objectRefreshInstanceIds: Set<Int>,
    )

    fun compileOneViewer(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): Result = compile(environment, input, prior, intent, null)

    internal fun compileOneViewerWithActions(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        actions: ActionsAvailableReq,
    ): Result = compile(environment, input, prior, intent, actions)

    private fun compile(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent,
        actions: ActionsAvailableReq?,
    ): Result {
        val editor = prior.editor()
        val draft = StateMapper.buildDraft(input, environment, prior, editor, actions)
        val orderResult = projectOrder(draft.gsm, input.snapshot, input.viewingSeatId, intent.orderPrompt, environment, editor)
        val supplementAnnotations = projectSupplements(input, prior, intent.supplements, draft, editor)
        val finalized =
            AnnotationFrameFinalizer.finalize(
                orderResult.gsm.annotationsList + supplementAnnotations,
                draft.firstAnnotationId,
            )
        val gsm =
            orderResult.gsm
                .toBuilder()
                .clearAnnotations()
                .addAllAnnotations(finalized.annotations)
                .build()

        editor.persistentAnnotations =
            editor.persistentAnnotations.copy(nextAnnotationId = finalized.nextId)
        val priorCursor = editor.viewerCursors[VIEWER_ID] ?: ViewerProjectionCursor()
        editor.viewerCursors[VIEWER_ID] =
            priorCursor.copy(
                previousSnapshot = orderResult.snapshot,
                pendingSubmittedTargets =
                    if (supplementAnnotations.consumedSubmittedTargets) null else priorCursor.pendingSubmittedTargets,
            )
        val next = editor.freeze()
        val output =
            draft.output.copy(
                idReallocations = draft.output.idReallocations + orderResult.idReallocations,
            )
        return Result(
            gsm = gsm,
            projectionSnapshot = orderResult.snapshot,
            output = output,
            transition =
                ProjectionTransition(
                    expectedRevision = prior.revision,
                    nextState = next,
                    acknowledgements =
                        ProjectionAcknowledgements(
                            consumedEarthbendResolutionVersions = output.consumedEarthbendResolutionVersions,
                            promptFacts = output.promptFactConsumption,
                        ),
                ),
            objectRefreshInstanceIds = draft.objectRefreshInstanceIds,
        )
    }

    private data class SupplementAnnotations(
        val annotations: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
        val consumedSubmittedTargets: Boolean,
    ) : List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> by annotations

    private fun projectSupplements(
        input: StateFrameInput,
        prior: ProjectionState,
        supplements: List<ProjectionSupplement>,
        draft: StateMapper.Draft,
        editor: ProjectionState.Editor,
    ): SupplementAnnotations {
        val annotations = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
        var submittedTargetsConsumed = false
        val frameIds = draft.idResolver
        for (supplement in supplements) {
            when (supplement) {
                ProjectionSupplement.NewTurnStarted ->
                    annotations += AnnotationBuilder.newTurnStarted(input.snapshot.phase.activePlayer)

                is ProjectionSupplement.PlayerSelectingTargets -> {
                    supplement.reserveTriggeredAbilityForgeId?.let { abilityId ->
                        editor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(abilityId))
                    }
                    annotations +=
                        AnnotationBuilder.playerSelectingTargets(
                            frameIds.cardIid(supplement.sourceForgeId),
                            supplement.seatId,
                        )
                }

                is ProjectionSupplement.ReserveTriggeredAbility ->
                    editor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(supplement.forgeAbilityId))

                is ProjectionSupplement.SubmitPendingTargets -> {
                    check(!submittedTargetsConsumed) { "Only one submitted-target fact may be consumed per viewer frame" }
                    val expected =
                        PendingSubmittedTargets(
                            supplement.spellInstanceId,
                            supplement.seatId,
                            supplement.version,
                        )
                    check(prior.viewerCursors[VIEWER_ID]?.pendingSubmittedTargets == expected) {
                        "Submitted-target fact does not match the prior viewer cursor"
                    }
                    annotations += AnnotationBuilder.playerSubmittedTargets(supplement.spellInstanceId, supplement.seatId)
                    submittedTargetsConsumed = true
                }
            }
        }
        return SupplementAnnotations(annotations, submittedTargetsConsumed)
    }

    private data class OrderResult(
        val gsm: GameStateMessage,
        val snapshot: GsmSnapshot,
        val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
    )

    private fun projectOrder(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        order: OrderPromptProjection?,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): OrderResult {
        order ?: return OrderResult(gsm, snapshot)
        order.candidateForgeIds.forEach(editor.identities::getOrAlloc)
        val move = order.move
        if (move == null) {
            return OrderResult(
                exposeOrderCandidates(gsm, snapshot, order.candidateForgeIds, viewingSeatId, environment, editor),
                snapshot,
            )
        }
        check(move.forgeCardIds == order.candidateForgeIds) {
            "Order move must describe the exact prompt candidate sequence"
        }
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destinationZoneId = ZoneIds.libraryOf(move.seatId)
        val moved =
            move.forgeCardIds.map { forgeCardId ->
                MovedCard(forgeCardId, editor.identities.realloc(forgeCardId))
            }
        val stagedSnapshot = stagedOrderSnapshot(snapshot, move, sourceZoneId, destinationZoneId)
        val sourceId = order.sourceForgeId?.let(editor.identities::getOrAlloc) ?: InstanceId(0)
        val stagedGsm =
            stagedOrderGsm(
                gsm,
                snapshot,
                stagedSnapshot,
                move,
                moved,
                sourceId,
                sourceZoneId,
                destinationZoneId,
                environment,
                editor,
            )
        editor.limboInstanceIds += moved.map { it.reallocation.old.value }
        moved.forEach { editor.protoZones[it.reallocation.new.value] = destinationZoneId }
        return OrderResult(stagedGsm, stagedSnapshot, moved.map { it.reallocation })
    }

    private data class MovedCard(
        val forgeCardId: ForgeCardId,
        val reallocation: InstanceIdRegistry.IdReallocation,
    )

    private fun stagedOrderSnapshot(
        snapshot: GsmSnapshot,
        move: OrderZoneMoveFact,
        sourceZoneId: Int,
        destinationZoneId: Int,
    ): GsmSnapshot {
        val moved = move.forgeCardIds.toSet()
        val zones = snapshot.zones.toMutableMap()
        zones[sourceZoneId]?.let { source ->
            zones[sourceZoneId] = source.copy(contents = source.contents.filterNot { it in moved })
        }
        zones[destinationZoneId]?.let { destination ->
            val remaining = destination.contents.filterNot { it in moved }
            zones[destinationZoneId] =
                destination.copy(
                    contents = if (move.putOnTop) move.forgeCardIds + remaining else remaining + move.forgeCardIds,
                )
        }
        return copySnapshot(snapshot, zones)
    }

    private fun copySnapshot(
        snapshot: GsmSnapshot,
        zones: Map<Int, ZoneSnapshot>,
    ): GsmSnapshot =
        GsmSnapshot(
            matchId = snapshot.matchId,
            gameStateId = snapshot.gameStateId,
            seats = snapshot.seats,
            zones = zones,
            boundCards = snapshot.boundCards,
            stack = snapshot.stack,
            phase = snapshot.phase,
            combat = snapshot.combat,
            abilityWordEntries = snapshot.abilityWordEntries,
            pendingTriggers = snapshot.pendingTriggers,
            capturedAt = snapshot.capturedAt,
            dayTime = snapshot.dayTime,
            activePlayerSpellsCastThisTurn = snapshot.activePlayerSpellsCastThisTurn,
        )

    private fun stagedOrderGsm(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        stagedSnapshot: GsmSnapshot,
        move: OrderZoneMoveFact,
        moved: List<MovedCard>,
        sourceId: InstanceId,
        sourceZoneId: Int,
        destinationZoneId: Int,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        val oldIds = moved.mapTo(mutableSetOf()) { it.reallocation.old.value }
        val newIds = moved.mapTo(mutableSetOf()) { it.reallocation.new.value }
        val builder = gsm.toBuilder()
        val replacementZones =
            listOfNotNull(
                stagedSnapshot.zones[sourceZoneId]?.let { zoneInfo(it, editor) },
                stagedSnapshot.zones[destinationZoneId]?.let { zoneInfo(it, editor) },
                limboZoneInfo(editor, moved.map { it.reallocation.old }),
            )
        builder.clearZones()
        builder.addAllZones(
            (gsm.zonesList.filterNot { it.zoneId in setOf(sourceZoneId, destinationZoneId, ZoneIds.LIMBO) } + replacementZones)
                .sortedBy { it.zoneId },
        )
        builder.clearGameObjects()
        builder.addAllGameObjects(gsm.gameObjectsList.filterNot { it.instanceId in oldIds || it.instanceId in newIds })
        for (movedCard in moved) {
            val card = snapshot.objects[movedCard.forgeCardId] ?: continue
            builder.addGameObjects(
                orderObject(card, movedCard.reallocation.old, ZoneIds.LIMBO, move.seatId.value, environment),
            )
            builder.addGameObjects(
                orderObject(card, movedCard.reallocation.new, destinationZoneId, move.seatId.value, environment),
            )
            builder.addAnnotations(AnnotationBuilder.objectIdChanged(movedCard.reallocation.old, movedCard.reallocation.new, sourceId))
            builder.addAnnotations(
                AnnotationBuilder.zoneTransfer(
                    movedCard.reallocation.new,
                    sourceZoneId,
                    destinationZoneId,
                    "Put",
                    affectorId = sourceId,
                ),
            )
        }
        return builder.build()
    }

    private fun exposeOrderCandidates(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        candidates: List<ForgeCardId>,
        viewingSeatId: Int,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        val builder = gsm.toBuilder()
        val existing =
            builder.gameObjectsList
                .withIndex()
                .associate { (index, obj) -> obj.instanceId to index }
                .toMutableMap()
        for (forgeCardId in candidates) {
            val card = snapshot.objects[forgeCardId] ?: continue
            val zone = snapshot.zones.values.firstOrNull { forgeCardId in it.contents } ?: continue
            val id = editor.identities.getOrAlloc(forgeCardId)
            val objectInfo = orderObject(card, id, zone.id, zone.owner?.value ?: viewingSeatId, environment, viewingSeatId)
            existing[id.value]?.let { builder.setGameObjects(it, objectInfo) } ?: run {
                existing[id.value] = builder.gameObjectsCount
                builder.addGameObjects(objectInfo)
            }
        }
        return builder.build()
    }

    private fun zoneInfo(
        zone: ZoneSnapshot,
        editor: ProjectionState.Editor,
    ): ZoneInfo {
        val builder =
            ZoneInfo
                .newBuilder()
                .setZoneId(zone.id)
                .setType(zone.type)
                .setVisibility(if (zone.type == ZoneType.Library) Visibility.Hidden else zone.visibility)
        zone.owner?.let { owner ->
            builder.ownerSeatId = owner.value
            if (zone.type == ZoneType.Hand || zone.type == ZoneType.Sideboard) builder.addViewers(owner.value)
        }
        zone.contents.forEach { builder.addObjectInstanceIds(editor.identities.getOrAlloc(it).value) }
        return builder.build()
    }

    private fun limboZoneInfo(
        editor: ProjectionState.Editor,
        extraIds: List<InstanceId>,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds((editor.limboInstanceIds.map(::InstanceId) + extraIds).distinct().map { it.value })
            .build()

    private fun orderObject(
        card: CardSnapshot,
        instanceId: InstanceId,
        zoneId: Int,
        ownerSeatId: Int,
        environment: StateProjectionEnvironment,
        viewerSeatId: Int = ownerSeatId,
    ): GameObjectInfo =
        ObjectMapper
            .buildFromSnapshot(
                cardSnap = card,
                instanceId = instanceId.value,
                zoneId = zoneId,
                ownerSeatId = ownerSeatId,
                cardProto = environment.cardProto,
                visibility = Visibility.Private,
            ).toBuilder()
            .addViewers(viewerSeatId)
            .build()

    private const val VIEWER_ID = 0
}
