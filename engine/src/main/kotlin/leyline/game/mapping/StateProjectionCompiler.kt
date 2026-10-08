package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationContext
import leyline.game.annotations.AnnotationFrameFinalizer
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.AppliedTransfer
import leyline.game.annotations.FinalizedAnnotationFrame
import leyline.game.annotations.TransferAnnotations
import leyline.game.annotations.TransferCategory
import leyline.game.bundle.GsmFrame
import leyline.game.event.GameEvent
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionAcknowledgements
import leyline.game.state.ProjectionOutput
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.ViewerProjectionCursor
import leyline.game.state.ZoneHandoff
import leyline.game.state.ZoneHandoffProjection
import leyline.game.state.ZoneProjectionLifecycle
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Finalizes one viewer's state projection as one tentative value transition. */
@Suppress("LargeClass") // Shared planning and per-view rendering are one cohesive projection lifecycle.
object StateProjectionCompiler {
    data class Result(
        val gsm: GameStateMessage,
        val projectionSnapshot: GsmSnapshot,
        val output: ProjectionOutput,
        val transition: ProjectionTransition,
        val objectRefreshInstanceIds: Set<Int>,
    )

    data class ViewerInput(
        val input: StateFrameInput,
        val intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        val actions: ActionsAvailableReq? = null,
        val decisionPending: Boolean = actions != null,
        val role: ProjectionViewerRole = ProjectionViewerRole.Player,
    )

    data class ViewerResult(
        val seatId: SeatId,
        val result: Result,
    )

    data class FoldResult(
        val viewers: List<ViewerResult>,
        val transition: ProjectionTransition,
        val phaseTransitionCommitAnnotation: AnnotationInfo? = null,
    )

    fun compileOneViewer(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): Result = compileViewers(environment, prior, listOf(ViewerInput(input, intent))).viewers.single().result

    internal fun compileOneViewerWithActions(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        actions: ActionsAvailableReq,
    ): Result = compileViewers(environment, prior, listOf(ViewerInput(input, intent, actions))).viewers.single().result

    @Suppress("LongMethod") // Shared projection stages run in one transaction.
    fun compileViewers(
        environment: StateProjectionEnvironment,
        prior: ProjectionState,
        viewers: List<ViewerInput>,
    ): FoldResult {
        require(viewers.isNotEmpty()) { "Projection requires at least one viewer" }
        require(viewers.map { it.input.viewingSeatId }.distinct().size == viewers.size) { "Viewer seats must be unique" }
        val editor = prior.editor()
        val canonical =
            viewers.firstOrNull { viewer ->
                viewer.intent.supplements.any { it is ProjectionSupplement.SubmitPendingTargets }
            } ?: viewers.first()
        val stagedCanonical = stagePreStackAbilities(canonical.input, canonical.intent.supplements)
        aliasAdmittedStackAbilities(stagedCanonical, editor)
        val planned = StateMapper.planSharedDraft(stagedCanonical, environment, editor)
        projectPrivateCardPrompt(
            planned.gsm,
            stagedCanonical.snapshot,
            canonical.input.viewingSeatId,
            canonical.intent.privateCardPrompt,
            environment,
            editor,
        )
        val plannedOrder =
            projectOrder(
                planned.gsm,
                stagedCanonical.snapshot,
                canonical.input.viewingSeatId,
                canonical.intent.orderPrompt,
                environment,
                editor,
            )
        val selectedOptions = projectSelectedCastOptions(canonical.intent.supplements, planned, editor)
        val supplementAnnotations = projectSupplements(canonical.input, prior, canonical.intent.supplements, planned, editor)
        val sagaIds =
            stagedCanonical.snapshot.objects
                .filterValues { it.isSaga }
                .keys
                .map { planned.idResolver.cardIid(it).value }
                .toSet()
        val finalized =
            finalizeAnnotations(
                plannedOrder.gsm.annotationsList + supplementAnnotations,
                supplementAnnotations.resolutionSourceOwners,
                sagaIds,
                planned.firstAnnotationId,
                stagedCanonical.previousSnapshot?.let { GsmFrame.from(it).step },
                editor.annotations,
            )
        val shared =
            planned.copy(
                gsm =
                    planned.gsm
                        .toBuilder()
                        .clearAnnotations()
                        .addAllPersistentAnnotations(selectedOptions)
                        .addAllAnnotations(finalized.annotations)
                        .build(),
                output =
                    planned.output.copy(
                        idReallocations = planned.output.idReallocations + plannedOrder.idReallocations,
                    ),
            )
        val phaseTransitionCommitFrame =
            if (ProjectionSupplement.PhaseTransition in canonical.intent.supplements) {
                val frame = GsmFrame.from(stagedCanonical.snapshot)
                AnnotationFrameFinalizer
                    .finalize(
                        listOf(
                            AnnotationBuilder.phaseOrStepModified(
                                stagedCanonical.snapshot.phase.activePlayer,
                                frame.phase.number,
                                frame.step.number,
                            ),
                        ),
                        finalized.nextId,
                    )
            } else {
                null
            }
        editor.persistentAnnotations =
            editor.persistentAnnotations.copy(
                nextAnnotationId = phaseTransitionCommitFrame?.nextId ?: finalized.nextId,
            )

        val projected =
            viewers.map { viewer ->
                renderViewer(
                    viewer,
                    shared,
                    plannedOrder,
                    finalized.annotations,
                    supplementAnnotations.consumedSubmittedTargets,
                    environment,
                    prior,
                    editor,
                )
            }
        val next = editor.freeze()
        val acknowledgements = mergeAcknowledgements(projected.map { it.second })
        val transition = ProjectionTransition(prior.revision, next, acknowledgements)
        return FoldResult(
            viewers =
                projected.map { (seatId, result) ->
                    ViewerResult(seatId, result.copy(transition = transition))
                },
            transition = transition,
            phaseTransitionCommitAnnotation = phaseTransitionCommitFrame?.annotations?.single(),
        )
    }

    private fun mergeAcknowledgements(results: List<Result>): ProjectionAcknowledgements =
        results.fold(ProjectionAcknowledgements()) { accumulated, result ->
            ProjectionAcknowledgements(
                consumedEarthbendResolutionVersions =
                    accumulated.consumedEarthbendResolutionVersions + result.output.consumedEarthbendResolutionVersions,
                promptFacts = accumulated.promptFacts.merge(result.output.promptFactConsumption),
            )
        }

    private fun viewerRevealAnnotations(
        annotations: List<AnnotationInfo>,
        current: GameStateMessage,
        previous: GameStateMessage?,
    ): List<AnnotationInfo> {
        val visible = current.gameObjectsList.filter { it.type == GameObjectType.RevealedCard }.mapTo(mutableSetOf()) { it.instanceId }
        val previouslyVisible =
            previous
                ?.gameObjectsList
                .orEmpty()
                .filter {
                    it.type == GameObjectType.RevealedCard
                }.mapTo(mutableSetOf()) { it.instanceId }
        return annotations.filter { annotation ->
            when {
                AnnotationType.RevealedCardCreated in annotation.typeList -> annotation.affectedIdsList.all { it in visible }
                AnnotationType.RevealedCardDeleted in annotation.typeList -> annotation.affectedIdsList.all { it in previouslyVisible }
                else -> true
            }
        }
    }

    @Suppress("LongParameterList")
    private fun renderViewer(
        viewer: ViewerInput,
        shared: StateMapper.Draft,
        plannedOrder: OrderResult,
        finalizedAnnotations: List<AnnotationInfo>,
        submittedTargetsConsumed: Boolean,
        environment: StateProjectionEnvironment,
        prior: ProjectionState,
        editor: ProjectionState.Editor,
    ): Pair<SeatId, Result> {
        val sharedViewerState =
            StateMapper.renderViewerFullState(
                shared,
                viewer.input.viewingSeatId,
                viewer.actions,
                viewer.role.seesSeatPrivateCards,
            )
        val visibleAnnotations =
            viewerRevealAnnotations(
                finalizedAnnotations,
                sharedViewerState,
                prior.viewerCursors[SeatId(viewer.input.viewingSeatId)]?.fullState,
            )
        val viewerAnnotations =
            if (
                submittedTargetsConsumed &&
                viewer.intent.supplements.none { it is ProjectionSupplement.SubmitPendingTargets }
            ) {
                visibleAnnotations.filterNot { AnnotationType.PlayerSubmittedTargets in it.typeList }
            } else {
                visibleAnnotations
            }
        val stagedInput = stagePreStackAbilities(viewer.input, viewer.intent.supplements)
        val projected =
            StateMapper.renderViewerDraft(
                shared,
                stagedInput,
                environment,
                prior,
                editor,
                viewer.actions,
                includePrivateObjects = viewer.role.seesSeatPrivateCards,
            )
        val rendered =
            if (viewer.decisionPending) {
                projected
            } else {
                projected.copy(
                    gsm =
                        projected.gsm
                            .toBuilder()
                            .setPendingMessageCount(0)
                            .build(),
                )
            }

        fun applyViewerOverlays(
            gsm: GameStateMessage,
            snapshot: GsmSnapshot,
        ): OrderResult {
            val annotated =
                gsm
                    .toBuilder()
                    .clearAnnotations()
                    .addAllAnnotations(viewerAnnotations)
                    .build()
            val privateOverlay =
                if (viewer.role == ProjectionViewerRole.Player) {
                    projectPrivateCardPrompt(
                        annotated,
                        stagedInput.snapshot,
                        viewer.input.viewingSeatId,
                        viewer.intent.privateCardPrompt,
                        environment,
                        editor,
                    )
                } else {
                    annotated
                }
            val orderOverlay =
                if (viewer.role == ProjectionViewerRole.Player) {
                    renderPlannedOrder(
                        privateOverlay,
                        stagedInput.snapshot,
                        viewer.input.viewingSeatId,
                        viewer.intent.orderPrompt,
                        plannedOrder,
                        environment,
                        editor,
                    )
                } else {
                    OrderResult(privateOverlay, snapshot)
                }
            return orderOverlay.copy(
                gsm =
                    orderOverlay.gsm
                        .toBuilder()
                        .clearAnnotations()
                        .addAllAnnotations(viewerAnnotations)
                        .build(),
            )
        }
        val finalizedOrderOverlay = applyViewerOverlays(rendered.gsm, rendered.projectionSnapshot)
        val fullState =
            applyViewerOverlays(
                sharedViewerState,
                rendered.projectionSnapshot,
            ).gsm
                .toBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(finalizedOrderOverlay.gsm.gameStateId)
                .clearPrevGameStateId()
                .clearAnnotations()
                .clearActions()
                .clearDiffDeletedInstanceIds()
                .setPendingMessageCount(0)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()
        val viewerSeatId = SeatId(viewer.input.viewingSeatId)
        val priorCursor = editor.viewerCursors[viewerSeatId] ?: ViewerProjectionCursor()
        val draft =
            rendered.copy(
                gsm = retirePrivateZoneObjects(finalizedOrderOverlay.gsm, fullState, priorCursor.fullState, shared.gsm),
                projectionSnapshot = finalizedOrderOverlay.snapshot,
                output =
                    rendered.output.copy(
                        idReallocations = rendered.output.idReallocations + finalizedOrderOverlay.idReallocations,
                    ),
            )
        editor.viewerCursors[viewerSeatId] =
            priorCursor.copy(
                previousSnapshot = draft.projectionSnapshot,
                fullState = fullState,
                pendingSubmittedTargets =
                    if (
                        submittedTargetsConsumed &&
                        viewer.intent.supplements.any { it is ProjectionSupplement.SubmitPendingTargets }
                    ) {
                        null
                    } else {
                        priorCursor.pendingSubmittedTargets
                    },
            )
        return viewerSeatId to
            Result(
                gsm = draft.gsm,
                projectionSnapshot = draft.projectionSnapshot,
                output = draft.output,
                transition = ProjectionTransition(prior.revision, prior),
                objectRefreshInstanceIds = draft.objectRefreshInstanceIds,
            )
    }

    /** Publish hand visibility changes and retire inaccessible companions for this viewer. */
    private fun retirePrivateZoneObjects(
        gsm: GameStateMessage,
        fullState: GameStateMessage,
        priorFullState: GameStateMessage?,
        neutralState: GameStateMessage,
    ): GameStateMessage {
        if (gsm.type != GameStateType.Diff || priorFullState == null) return gsm
        val currentIds = fullState.gameObjectsList.mapTo(mutableSetOf()) { it.instanceId }
        val handZones = setOf(ZoneIds.P1_HAND, ZoneIds.P2_HAND)
        val currentHandIds =
            neutralState.zonesList
                .filter { it.zoneId in handZones }
                .flatMapTo(mutableSetOf()) { it.objectInstanceIdsList }
        val priorObjects = priorFullState.gameObjectsList.associateBy { it.instanceId }
        val emittedIds = gsm.gameObjectsList.mapTo(mutableSetOf()) { it.instanceId }
        val changedHandVisibility =
            fullState.gameObjectsList.filter { current ->
                val previous = priorObjects[current.instanceId]
                current.zoneId in handZones &&
                    current.instanceId !in emittedIds &&
                    previous != null &&
                    (current.visibility != previous.visibility || current.viewersList != previous.viewersList)
            }
        val hiddenHandObjects =
            priorFullState.gameObjectsList
                .filter {
                    it.visibility in setOf(Visibility.Private, Visibility.Public) &&
                        it.zoneId in handZones &&
                        it.instanceId in currentHandIds &&
                        it.instanceId !in currentIds
                }.map { ZoneMapper.hiddenCardObject(it.instanceId, it.zoneId, SeatId(it.ownerSeatId)) }
        val retired =
            priorFullState.gameObjectsList
                .filter {
                    (
                        (
                            it.visibility in setOf(Visibility.Private, Visibility.Public) &&
                                it.zoneId in handZones &&
                                it.parentId != 0 &&
                                it.parentId in currentHandIds
                        ) ||
                            (
                                it.visibility == Visibility.Private &&
                                    it.parentId != 0 &&
                                    it.zoneId in setOf(ZoneIds.P1_LIBRARY, ZoneIds.P2_LIBRARY)
                            )
                    ) &&
                        it.instanceId !in currentIds
                }.map { it.instanceId }
        return gsm
            .toBuilder()
            .addAllGameObjects(changedHandVisibility + hiddenHandObjects)
            .clearDiffDeletedInstanceIds()
            .addAllDiffDeletedInstanceIds((gsm.diffDeletedInstanceIdsList + retired).distinct())
            .build()
    }

    private fun leyline.game.state.PromptFactConsumption.merge(
        next: leyline.game.state.PromptFactConsumption,
    ): leyline.game.state.PromptFactConsumption =
        leyline.game.state.PromptFactConsumption(
            choiceResults = choiceResults + next.choiceResults,
            staleReveals = staleReveals + next.staleReveals,
            convokePayments = convokePayments + next.convokePayments,
            collectEvidenceCosts = collectEvidenceCosts + next.collectEvidenceCosts,
            targetSpecs = targetSpecs + next.targetSpecs,
        )

    private fun finalizeAnnotations(
        annotations: List<AnnotationInfo>,
        resolutionSourceOwners: Map<Int, Int>,
        sagaInstanceIds: Set<Int>,
        firstId: Int,
        previousStep: Step?,
        journal: leyline.game.state.AnnotationProjectionState.Planner,
    ): FinalizedAnnotationFrame {
        val ordered = AnnotationOrderEnforcer.enforce(annotations, previousStep, resolutionSourceOwners, sagaInstanceIds)
        return AnnotationFrameFinalizer.numberOrdered(retainResolutionMarkers(ordered, journal), firstId)
    }

    /** A prompt may start resolution in an earlier frame than its effects and completion. */
    private fun retainResolutionMarkers(
        annotations: List<AnnotationInfo>,
        journal: leyline.game.state.AnnotationProjectionState.Planner,
    ): List<AnnotationInfo> =
        annotations.filter { annotation ->
            when {
                AnnotationType.ResolutionStart in annotation.typeList -> journal.startResolution(annotation.affectorId)
                AnnotationType.ResolutionComplete in annotation.typeList -> {
                    journal.completeResolution(annotation.affectorId)
                    true
                }
                else -> true
            }
        }

    private data class SupplementAnnotations(
        val annotations: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
        val consumedSubmittedTargets: Boolean,
        val resolutionSourceOwners: Map<Int, Int>,
    ) : List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> by annotations

    private fun projectSelectedCastOptions(
        supplements: List<ProjectionSupplement>,
        draft: StateMapper.Draft,
        editor: ProjectionState.Editor,
    ): List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> {
        val rows =
            supplements.filterIsInstance<ProjectionSupplement.SelectedCastOption>().map { option ->
                AnnotationBuilder.castingTimeOption(
                    draft.idResolver.cardIid(option.sourceForgeId),
                    wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.CastThroughAbility,
                    leyline.bridge.types.GrpId(option.alternateCostGrpId),
                    leyline.bridge.types.GrpId(option.castAbilityGrpId),
                )
            }
        val added = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
        for (row in rows) {
            val state = editor.persistentAnnotations
            if (state.activeAnnotations.values.any { it.toBuilder().clearId().build() == row }) continue
            val numbered = row.toBuilder().setId(state.nextPersistentId).build()
            editor.persistentAnnotations =
                state.copy(
                    activeAnnotations = state.activeAnnotations + (numbered.id to numbered),
                    nextPersistentId = state.nextPersistentId + 1,
                )
            added += numbered
        }
        return added
    }

    private fun projectSupplements(
        input: StateFrameInput,
        prior: ProjectionState,
        supplements: List<ProjectionSupplement>,
        draft: StateMapper.Draft,
        editor: ProjectionState.Editor,
    ): SupplementAnnotations {
        val annotations = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
        var submittedTargetsConsumed = false
        val resolutionSourceOwners = mutableMapOf<Int, Int>()
        val frameIds = draft.idResolver
        for (supplement in supplements) {
            when (supplement) {
                is ProjectionSupplement.ResolutionStarted -> {
                    val entry = supplement.entry
                    val instanceId =
                        if (entry.isSpell) {
                            frameIds.cardIid(entry.forgeCardId)
                        } else {
                            InstanceId(AnnotationContext.stackAbilityIid(entry.forgeAbilityId, entry.forgeCardId, frameIds))
                        }
                    val identity = editor.annotations.ability(instanceId.value)
                    val unresolved =
                        if (entry.isSpell) {
                            editor.protoZones[instanceId.value] == ZoneIds.STACK &&
                                editor.annotations.pendingSpellResolution(entry.forgeCardId, entry.sourceCardGrpId) == null
                        } else {
                            identity != null
                        }
                    val grpId = if (entry.isSpell) entry.sourceCardGrpId else identity?.abilityGrpId ?: entry.grpId
                    if (unresolved) {
                        annotations += AnnotationBuilder.resolutionStart(instanceId, leyline.bridge.types.GrpId(grpId))
                        if (!entry.isSpell) resolutionSourceOwners[frameIds.cardIid(entry.forgeCardId).value] = instanceId.value
                    }
                }
                ProjectionSupplement.NewTurnStarted ->
                    annotations += AnnotationBuilder.newTurnStarted(input.snapshot.phase.activePlayer)

                ProjectionSupplement.PhaseTransition -> {
                    val frame = GsmFrame.from(input.snapshot)
                    repeat(2) {
                        annotations +=
                            AnnotationBuilder.phaseOrStepModified(
                                input.snapshot.phase.activePlayer,
                                frame.phase.number,
                                frame.step.number,
                            )
                    }
                }

                is ProjectionSupplement.PlayerSelectingTargets -> {
                    val targetInstanceId =
                        supplement.stackAbilityForgeId
                            ?.let(frameIds::triggerStackAbilityIid)
                            ?: frameIds.cardIid(supplement.sourceForgeId)
                    annotations +=
                        AnnotationBuilder.playerSelectingTargets(
                            targetInstanceId,
                            supplement.seatId,
                        )
                }

                is ProjectionSupplement.ReserveTriggeredAbility ->
                    editor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(supplement.forgeAbilityId))

                is ProjectionSupplement.SelectedCastOption,
                is ProjectionSupplement.PreStackAbility,
                is ProjectionSupplement.PreStackSpell,
                -> Unit

                is ProjectionSupplement.SubmitPendingTargets -> {
                    check(!submittedTargetsConsumed) { "Only one submitted-target fact may be consumed per viewer frame" }
                    val expected =
                        PendingSubmittedTargets(
                            supplement.spellInstanceId,
                            supplement.seatId,
                            supplement.version,
                        )
                    check(prior.viewerCursors[SeatId(input.viewingSeatId)]?.pendingSubmittedTargets == expected) {
                        "Submitted-target fact does not match the prior viewer cursor"
                    }
                    annotations += AnnotationBuilder.playerSubmittedTargets(supplement.spellInstanceId, supplement.seatId)
                    submittedTargetsConsumed = true
                }

                is ProjectionSupplement.StaticParityChoice -> {
                    val sourceId = frameIds.cardIid(supplement.sourceForgeId)
                    val sourceGrpId =
                        input.snapshot.boundCards
                            .getValue(supplement.sourceForgeId)
                            .snapshot.grpId
                    annotations += AnnotationBuilder.resolutionStart(sourceId, leyline.bridge.types.GrpId(sourceGrpId))
                    annotations +=
                        AnnotationBuilder.selectNDecoration(
                            sourceId,
                            optionIndex = 0,
                            affectedObjectIds = supplement.evenForgeIds.map(frameIds::cardIid),
                        )
                    annotations +=
                        AnnotationBuilder.selectNDecoration(
                            sourceId,
                            optionIndex = 1,
                            affectedObjectIds = supplement.oddForgeIds.map(frameIds::cardIid),
                        )
                }
            }
        }
        return SupplementAnnotations(annotations, submittedTargetsConsumed, resolutionSourceOwners.toMap())
    }

    private data class OrderResult(
        val gsm: GameStateMessage,
        val snapshot: GsmSnapshot,
        val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
    )

    private fun stagePreStackAbilities(
        input: StateFrameInput,
        supplements: List<ProjectionSupplement>,
    ): StateFrameInput {
        val abilities = supplements.filterIsInstance<ProjectionSupplement.PreStackAbility>()
        val spells = supplements.filterIsInstance<ProjectionSupplement.PreStackSpell>()
        val reservations = supplements.filterIsInstance<ProjectionSupplement.ReserveTriggeredAbility>()
        if (abilities.isEmpty() && spells.isEmpty() && reservations.isEmpty()) return input

        var stack = input.snapshot.stack
        var zones = input.snapshot.zones
        var boundCards = input.snapshot.boundCards
        for (spell in spells) {
            val bound = spell.card
            val card = bound.snapshot
            val currentZone = zones.values.firstOrNull { card.forgeCardId in it.contents }
            if (currentZone != null && currentZone.id != leyline.game.mapping.ZoneIds.LIMBO) continue
            if (stack.entries.none { it.forgeCardId == card.forgeCardId && it.isSpell }) {
                stack =
                    StackSnapshot(
                        listOf(
                            StackEntry(
                                forgeCardId = card.forgeCardId,
                                controller = card.controller,
                                owner = card.owner,
                                grpId = card.grpId,
                                sourceCardGrpId = card.grpId,
                                isSpell = true,
                                targets = emptyList(),
                            ),
                        ) + stack.entries,
                    )
            }
            boundCards = boundCards + (card.forgeCardId to bound)
            zones =
                zones.mapValues { (_, zone) ->
                    if (card.forgeCardId in zone.contents) zone.copy(contents = zone.contents - card.forgeCardId) else zone
                }
            val stackZone = checkNotNull(zones[leyline.game.mapping.ZoneIds.STACK])
            if (card.forgeCardId !in stackZone.contents) {
                zones = zones + (stackZone.id to stackZone.copy(contents = listOf(card.forgeCardId) + stackZone.contents))
            }
        }
        for (ability in abilities) {
            val existing = stack.entries.firstOrNull { it.forgeAbilityId == ability.forgeAbilityId }
            if (existing != null) {
                check(
                    existing.forgeCardId == ability.sourceForgeCardId &&
                        existing.grpId == ability.abilityGrpId &&
                        existing.sourceCardGrpId == ability.sourceCardGrpId &&
                        existing.owner == ability.ownerSeatId &&
                        existing.controller == ability.controllerSeatId &&
                        existing.targets == ability.targetForgeCardIds,
                ) { "Pre-stack ability conflicts with the existing frame entry" }
                continue
            }
            stack =
                StackSnapshot(
                    listOf(
                        StackEntry(
                            forgeCardId = ability.sourceForgeCardId,
                            controller = ability.controllerSeatId,
                            owner = ability.ownerSeatId,
                            grpId = ability.abilityGrpId,
                            sourceCardGrpId = ability.sourceCardGrpId,
                            isSpell = false,
                            isActivatedAbility = ability.isActivatedAbility,
                            targets = ability.targetForgeCardIds,
                            forgeAbilityId = ability.forgeAbilityId,
                        ),
                    ) + stack.entries,
                )
        }
        for (reservation in reservations) {
            if (stack.entries.any { it.forgeAbilityId == reservation.forgeAbilityId }) continue
            input.previousSnapshot
                ?.stack
                ?.entries
                ?.singleOrNull { it.forgeAbilityId == reservation.forgeAbilityId }
                ?.let { stack = StackSnapshot(listOf(it) + stack.entries) }
        }
        return input.copy(snapshot = copySnapshot(input.snapshot, zones = zones, boundCards = boundCards, stack = stack))
    }

    private fun aliasAdmittedStackAbilities(
        input: StateFrameInput,
        editor: ProjectionState.Editor,
    ) {
        val priorEntries =
            input.previousSnapshot
                ?.stack
                ?.entries
                .orEmpty()
        if (priorEntries.isEmpty()) return
        for (event in input.events.events.filterIsInstance<GameEvent.SpellCast>()) {
            if (!event.isAbility || event.isTrigger || event.rootAbilityForgeId == 0) continue
            val admittedForgeIds =
                listOf(event.abilityForgeId, event.stackAbilityForgeId)
                    .filter { it != 0 }
                    .toSet()
            if (admittedForgeIds.isEmpty()) continue
            val admitted =
                input.snapshot.stack.entries.singleOrNull {
                    it.forgeCardId == event.cardId && it.forgeAbilityId in admittedForgeIds
                } ?: continue
            val prior =
                priorEntries.singleOrNull { it.forgeAbilityId == event.rootAbilityForgeId } ?: continue
            if (!prior.isActivatedAbility || prior.forgeCardId != admitted.forgeCardId) continue
            if (prior.forgeAbilityId == admitted.forgeAbilityId) continue
            editor.identities.alias(
                FrameIdResolver.triggerStackAbilityForgeId(prior.forgeAbilityId),
                FrameIdResolver.triggerStackAbilityForgeId(admitted.forgeAbilityId),
            )
        }
    }

    private fun projectPrivateCardPrompt(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        prompt: PrivateCardPromptProjection?,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        prompt ?: return gsm
        prompt.sourceForgeId?.let(editor.identities::getOrAlloc)
        prompt.candidateForgeIds.forEach(editor.identities::getOrAlloc)
        return exposePrivateCandidates(gsm, snapshot, prompt.candidateForgeIds, viewingSeatId, environment, editor)
    }

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
                exposePrivateCandidates(gsm, snapshot, order.candidateForgeIds, viewingSeatId, environment, editor),
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
        val (stagedGsm, lifecycle) =
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
        lifecycle.applyTo(editor)
        return OrderResult(stagedGsm, stagedSnapshot, moved.map { it.reallocation })
    }

    private fun renderPlannedOrder(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        order: OrderPromptProjection?,
        planned: OrderResult,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): OrderResult {
        order ?: return OrderResult(gsm, snapshot)
        val move = order.move
        if (move == null) {
            return OrderResult(
                exposePrivateCandidates(gsm, snapshot, order.candidateForgeIds, viewingSeatId, environment, editor),
                snapshot,
            )
        }
        val moved = order.candidateForgeIds.zip(planned.idReallocations).map { (forgeCardId, ids) -> MovedCard(forgeCardId, ids) }
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destinationZoneId = ZoneIds.libraryOf(move.seatId)
        val sourceId = order.sourceForgeId?.let(editor.identities::getOrAlloc) ?: InstanceId(0)
        return OrderResult(
            stagedOrderGsm(
                gsm,
                snapshot,
                planned.snapshot,
                move,
                moved,
                sourceId,
                sourceZoneId,
                destinationZoneId,
                environment,
                editor,
            ).first,
            planned.snapshot,
            planned.idReallocations,
        )
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
        zones: Map<Int, ZoneSnapshot> = snapshot.zones,
        boundCards: Map<leyline.bridge.types.ForgeCardId, leyline.game.snapshot.BoundCard> = snapshot.boundCards,
        stack: StackSnapshot = snapshot.stack,
    ): GsmSnapshot =
        GsmSnapshot(
            matchId = snapshot.matchId,
            gameStateId = snapshot.gameStateId,
            seats = snapshot.seats,
            zones = zones,
            boundCards = boundCards,
            stack = stack,
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
    ): Pair<GameStateMessage, ZoneProjectionLifecycle> {
        val oldIds = moved.mapTo(mutableSetOf()) { it.reallocation.old.value }
        val newIds = moved.mapTo(mutableSetOf()) { it.reallocation.new.value }
        val originalIds = moved.associate { it.forgeCardId to it.reallocation.old }
        val replacementZones =
            listOfNotNull(
                stagedSnapshot.zones[sourceZoneId]?.let { zoneInfo(it, editor, originalIds) },
                stagedSnapshot.zones[destinationZoneId]?.let { zoneInfo(it, editor, originalIds) },
                limboZoneInfo(editor),
            )
        val projection =
            ZoneHandoffProjection(
                gsm.gameObjectsList.filterNot { it.instanceId in oldIds || it.instanceId in newIds },
                gsm.zonesList.filterNot { it.zoneId in setOf(sourceZoneId, destinationZoneId, ZoneIds.LIMBO) } + replacementZones,
            )
        val annotations = mutableListOf<AnnotationInfo>()
        for (movedCard in moved) {
            val handoff = ZoneHandoff.fromRealloc(movedCard.reallocation, destinationZoneId)
            val card = snapshot.objects[movedCard.forgeCardId]
            if (card == null) {
                projection.apply(handoff)
                continue
            }
            projection.objects.add(
                orderObject(card, movedCard.reallocation.old, destinationZoneId, move.seatId.value, environment),
            )
            projection.applyRetainingObject(
                handoff,
                projection.objects.lastIndex,
            )
            annotations +=
                TransferAnnotations
                    .annotationsForTransfer(
                        AppliedTransfer(
                            origId = movedCard.reallocation.old.value,
                            newId = movedCard.reallocation.new.value,
                            category = TransferCategory.Put,
                            srcZoneId = sourceZoneId,
                            destZoneId = destinationZoneId,
                            forgeCardId = movedCard.forgeCardId,
                            grpId = card.grpId,
                            ownerSeatId = move.seatId.value,
                            affectorId = sourceId.value,
                        ),
                        move.seatId,
                    ).first
        }
        return gsm
            .toBuilder()
            .clearZones()
            .addAllZones(projection.zones.sortedBy { it.zoneId })
            .clearGameObjects()
            .addAllGameObjects(projection.objects)
            .addAllAnnotations(annotations)
            .build() to projection.lifecycle()
    }

    private fun exposePrivateCandidates(
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
            if (snapshot.objects[forgeCardId] == null) continue
            val zone = snapshot.zones.values.firstOrNull { forgeCardId in it.contents } ?: continue
            val id = editor.identities.getOrAlloc(forgeCardId)
            val family = mutableListOf<GameObjectInfo>()
            ZoneMapper.addPlayerCardObjects(
                snapshot,
                forgeCardId,
                id.value,
                zone.id,
                zone.owner ?: leyline.bridge.types.SeatId(viewingSeatId),
                environment,
                editor.identities::getOrAlloc,
                Visibility.Private,
                "private candidate",
                family,
                viewers = setOf(viewingSeatId),
            )
            snapshot.boundCards[forgeCardId]?.let { bound ->
                family += LinkedFaceCompanionProjector.companions(bound.linkedFaces, family.first(), editor, environment)
            }
            for (objectInfo in family) {
                existing[objectInfo.instanceId]?.let { builder.setGameObjects(it, objectInfo) } ?: run {
                    existing[objectInfo.instanceId] = builder.gameObjectsCount
                    builder.addGameObjects(objectInfo)
                }
            }
        }
        return builder.build()
    }

    private fun zoneInfo(
        zone: ZoneSnapshot,
        editor: ProjectionState.Editor,
        originalIds: Map<ForgeCardId, InstanceId>,
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
        zone.contents.forEach { builder.addObjectInstanceIds((originalIds[it] ?: editor.identities.getOrAlloc(it)).value) }
        return builder.build()
    }

    private fun limboZoneInfo(editor: ProjectionState.Editor): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds(editor.limboInstanceIds)
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
}
