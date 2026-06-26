package leyline.game.mapping

import forge.game.card.CardLists
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.codes.KeywordGrpIds
import leyline.game.codes.QualificationType
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PreparedRole
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.ColorProductionKind
import leyline.game.state.CommanderDesignationKind
import leyline.game.state.DayNightDesignationKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.FaceDownDisguiseKind
import leyline.game.state.GameBridge
import leyline.game.state.HolderRecord
import leyline.game.state.LinkInfoChoiceKind
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.PreparedDesignationKind
import leyline.game.state.QualificationKind
import leyline.game.state.TemporaryPermanentKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Snap- or event-derived persistent annotation inputs for one GSM build. */
internal data class PersistentFeedSet(
    val perKind: Map<PersistentAnnotationKind, List<AnnotationInfo>> = emptyMap(),
) {
    operator fun get(kind: PersistentAnnotationKind): List<AnnotationInfo> = perKind[kind].orEmpty()
}

internal data class PersistentFeedBuildResult(
    val feeds: PersistentFeedSet,
    val currentHolders: List<HolderRecord>,
)

private data class TemporaryPermanentFeedResult(
    val temporaryPermanent: List<AnnotationInfo>,
    val delayedTriggerAffectees: List<AnnotationInfo>,
    val currentHolders: List<HolderRecord>,
)

internal class PersistentFeedContext(
    private val bridge: GameBridge,
    private val frameIds: FrameIdResolver,
) {
    fun allocatedCardIid(forgeCardId: ForgeCardId): InstanceId = bridge.getOrAllocInstanceId(forgeCardId)

    fun visibleCardIid(forgeCardId: ForgeCardId): InstanceId = frameIds.cardIid(forgeCardId)
}

internal object PersistentFeedBuilder {
    fun build(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        decayedCleanupSourcesThisGsm: Set<ForgeCardId>,
        transferResult: TransferResult,
    ): PersistentFeedBuildResult {
        val qualification = buildQualificationAnnotations(snap, bridge, frameIds)
        val temporaryPermanent =
            buildTemporaryPermanentAnnotations(
                snap,
                bridge,
                frameIds,
                decayedCleanupSourcesThisGsm,
                transferResult,
            )
        val abilityWord = buildAbilityWordAnnotations(events, snap, prev, bridge, frameIds)
        val context = PersistentFeedContext(bridge, frameIds)
        val designations = buildDesignationAnnotations(snap, context)
        val dayNightDesignation = buildDayNightDesignationAnnotations(snap)
        val faceDownDisguise = buildFaceDownDisguiseAnnotations(snap, frameIds)
        val colorProduction = buildColorProductionAnnotations(snap, frameIds)
        val linkInfo = buildLinkInfoAnnotations(snap, frameIds, bridge)

        return PersistentFeedBuildResult(
            feeds =
                PersistentFeedSet(
                    perKind =
                        mapOf(
                            QualificationKind to qualification,
                            TemporaryPermanentKind to temporaryPermanent.temporaryPermanent,
                            DelayedTriggerAffecteesKind to temporaryPermanent.delayedTriggerAffectees,
                            AbilityWordActiveKind to abilityWord,
                            DayNightDesignationKind to dayNightDesignation,
                            FaceDownDisguiseKind to faceDownDisguise,
                            ColorProductionKind to colorProduction,
                            LinkInfoChoiceKind to linkInfo,
                        ) + designations,
                ),
            currentHolders = temporaryPermanent.currentHolders,
        )
    }

    private fun buildQualificationAnnotations(
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.objects.values
            .filter { it.isOnAdventure }
            .map { AnnotationBuilder.qualification(instanceId = frameIds.cardIid(it.forgeCardId)) } +
            suspectedQualificationAnnotations(snap, frameIds) +
            CombatQualificationScanner.scan(snap, bridge, frameIds)

    private fun suspectedQualificationAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val menaceGrpId = KeywordGrpIds.forKeyword("Menace")?.let(::GrpId) ?: return emptyList()
        return snap.boundCards.values
            .asSequence()
            .filter { it.snapshot.isOnBattlefield && it.designations.isSuspected }
            .flatMap { card ->
                val iid = frameIds.cardIid(card.forgeCardId)
                sequenceOf(
                    AnnotationBuilder.qualification(
                        affectorId = iid,
                        instanceId = iid,
                        grpId = menaceGrpId,
                        qualificationType = QualificationType.CombatKeyword,
                        sourceParent = iid,
                    ),
                    AnnotationBuilder.qualification(
                        affectorId = iid,
                        instanceId = iid,
                        grpId = AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID,
                        qualificationType = QualificationType.CantBlock,
                        sourceParent = iid,
                    ),
                )
            }.toList()
    }

    private fun buildTemporaryPermanentAnnotations(
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        decayedCleanupSourcesThisGsm: Set<ForgeCardId>,
        transferResult: TransferResult,
    ): TemporaryPermanentFeedResult {
        val eotTokens = snap.objects.values.filter { it.isOnBattlefield && it.endOfTurnLeavePlay }
        val tokenSources: Map<CardSnapshot, ForgeCardId?> =
            eotTokens.associateWith { tokenSourceForgeId(it.forgeCardId, bridge) }
        val decayedCleanupHolders =
            decayedCleanupHoldersFromSnap(
                decayedCleanupSourcesThisGsm,
                snap,
                bridge,
                frameIds,
                transferResult,
            )
        val temporaryPermanent =
            eotTokens.map { token ->
                val tokenIid = bridge.getOrAllocInstanceId(token.forgeCardId)
                val sourceForgeId = tokenSources[token]
                val mobilizeCleanup =
                    sourceForgeId?.let { source ->
                        mobilizeCleanupGrpIdForSource(source, snap)?.let { cleanupGrpId -> source to cleanupGrpId }
                    }
                // Holder iid is the per-trigger affector for Mobilize. Generic
                // EOT-sacrifice copies keep the token iid affector until their
                // delayed-trigger holder shape is modeled.
                val affectorIid =
                    if (mobilizeCleanup != null) {
                        holderInstanceIdFor(mobilizeCleanup.first, bridge)
                    } else {
                        tokenIid
                    }
                AnnotationBuilder.temporaryPermanent(
                    tokenInstanceId = tokenIid,
                    abilityGrpId = mobilizeCleanup?.second?.let { GrpId(it) } ?: AnnotationConstants.EOT_SACRIFICE_GRP_ID,
                    affectorId = affectorIid,
                )
            } +
                decayedCleanupHolders.map { holder ->
                    AnnotationBuilder.temporaryPermanent(
                        tokenInstanceId = InstanceId(holder.parentIid),
                        abilityGrpId = GrpId(holder.cleanupGrpId),
                        affectorId = InstanceId(holder.iid),
                    )
                }
        val currentHolders = mutableListOf<HolderRecord>()
        currentHolders.addAll(decayedCleanupHolders)
        val delayedTriggerAffectees =
            eotTokens
                .groupBy { tokenSources[it] to it.controller.value }
                .filterValues { it.isNotEmpty() }
                .mapNotNull { (key, tokens) ->
                    val (rawSourceForgeId, seat) = key
                    val sourceForgeId = rawSourceForgeId ?: return@mapNotNull null
                    val cleanupGrpId = mobilizeCleanupGrpIdForSource(sourceForgeId, snap) ?: return@mapNotNull null
                    val tokenIds = tokens.map { bridge.getOrAllocInstanceId(it.forgeCardId) }
                    val holderIid = holderInstanceIdFor(sourceForgeId, bridge)
                    val keywordGrpId = mobilizeKeywordGrpIdForSource(sourceForgeId, snap) ?: 0
                    val sourceIid = bridge.getOrAllocInstanceId(sourceForgeId).value
                    currentHolders.add(
                        HolderRecord(
                            iid = holderIid.value,
                            ownerSeat = seat,
                            objectSourceGrpId = keywordGrpId,
                            parentIid = sourceIid,
                            cleanupGrpId = cleanupGrpId,
                        ),
                    )
                    AnnotationBuilder.delayedTriggerAffectees(
                        triggerHolderId = holderIid,
                        tokenInstanceIds = tokenIds,
                        abilityGrpId = GrpId(cleanupGrpId),
                    )
                }
        return TemporaryPermanentFeedResult(
            temporaryPermanent = temporaryPermanent,
            delayedTriggerAffectees = delayedTriggerAffectees,
            currentHolders = currentHolders,
        )
    }

    private fun buildAbilityWordAnnotations(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.abilityWordEntries.map { entry ->
            AnnotationBuilder.abilityWordActive(
                instanceId = InstanceId(entry.instanceId),
                abilityWordName = entry.abilityWordName,
                value = entry.value,
                threshold = entry.threshold,
                abilityGrpId = entry.abilityGrpId?.let { GrpId(it) },
                affectorId = InstanceId(entry.affectorId ?: entry.instanceId),
                affectedIds = entry.affectedIds.ifEmpty { listOf(entry.instanceId) }.map { InstanceId(it) },
            )
        } +
            collectEvidenceAbilityWordPersistentFromPrompt(events, bridge, frameIds) +
            convokeCountAbilityWordPersistentFromPrompt(snap, bridge, frameIds) +
            trainingAbilityWordPersistentFromEvents(events, snap, prev, bridge, frameIds)

    private fun convokeCountAbilityWordPersistentFromPrompt(
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val stackForgeIds =
            snap.zones[ZoneIds.STACK]
                ?.contents
                ?.toSet()
                .orEmpty()
        if (stackForgeIds.isEmpty()) return emptyList()
        val annotations = mutableListOf<AnnotationInfo>()
        for (seatValue in bridge.allSeatIds().sorted()) {
            val paymentsBySource = bridge.promptBridge(SeatId(seatValue)).journal.activeConvokePayments()
            for ((sourceForgeCardId, payments) in paymentsBySource) {
                if (payments.isEmpty() || sourceForgeCardId !in stackForgeIds) continue
                annotations.add(
                    AnnotationBuilder.abilityWordActive(
                        instanceId = frameIds.cardIid(sourceForgeCardId),
                        abilityWordName = "ConvokeCount",
                        value = payments.size,
                        abilityGrpId = GrpId(KeywordAbilityIds.CONVOKE),
                    ),
                )
            }
        }
        return annotations
    }

    private fun buildDesignationAnnotations(
        snap: GsmSnapshot,
        context: PersistentFeedContext,
    ): Map<PersistentAnnotationKind, List<AnnotationInfo>> {
        val simpleRows =
            CardStateDesignations.simplePersistent.associate { spec ->
                val kind = spec.persistentKind ?: error("simple persistent designation missing kind: ${spec.kind}")
                val emit = spec.persistentEmitter ?: error("simple persistent designation missing emitter: ${spec.kind}")
                kind to
                    snap.boundCards.values.mapNotNull { bound ->
                        if (!spec.readRole(bound)) return@mapNotNull null
                        emit(context.allocatedCardIid(bound.forgeCardId))
                    }
            }
        val prepared =
            snap.boundCards.values
                .mapNotNull { bound ->
                    val source = bound.designations.prepared as? PreparedRole.Source ?: return@mapNotNull null
                    AnnotationBuilder.preparedDesignation(
                        instanceId = context.allocatedCardIid(bound.forgeCardId),
                        preparedCopyInstanceId = context.allocatedCardIid(source.copyForgeCardId),
                    )
                }
        val commander =
            snap.boundCards.values
                .filter { it.designations.isCommander && it.snapshot.grpId > 0 }
                .flatMap { bound ->
                    val iid = context.visibleCardIid(bound.forgeCardId)
                    val grpId = GrpId(bound.snapshot.grpId)
                    val colorIdentity = bound.designations.commanderColorIdentity
                    val tax = bound.designations.commanderTax
                    listOf(
                        AnnotationBuilder.commanderPlayerDesignation(
                            seatId = bound.snapshot.owner,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                        AnnotationBuilder.commanderObjectDesignation(
                            instanceId = iid,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                    )
                }
        return simpleRows +
            mapOf(
                PreparedDesignationKind to prepared,
                CommanderDesignationKind to commander,
            )
    }

    private fun buildDayNightDesignationAnnotations(snap: GsmSnapshot): List<AnnotationInfo> =
        snap.dayTime?.let { isNight ->
            listOf(
                AnnotationBuilder.dayNightDesignation(
                    designationType =
                        if (isNight) {
                            AnnotationConstants.DESIGNATION_TYPE_NIGHT
                        } else {
                            AnnotationConstants.DESIGNATION_TYPE_DAY
                        },
                    activePlayerSpellCount = snap.activePlayerSpellsCastThisTurn,
                ),
            )
        } ?: emptyList()

    private fun buildFaceDownDisguiseAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values
            .mapNotNull { bound ->
                if (!bound.snapshot.isFaceDownDisguise) return@mapNotNull null
                AnnotationBuilder.faceDownPersistent(
                    instanceId = frameIds.cardIid(bound.forgeCardId),
                    reason = AnnotationConstants.FACEDOWN_REASON_DISGUISE,
                    abilityGrpId = GrpId(KeywordAbilityIds.DISGUISE),
                )
            }

    private fun buildColorProductionAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values.mapNotNull { bound ->
            if (!bound.snapshot.isOnBattlefield) return@mapNotNull null
            val colors = bound.snapshot.manaProductionColors
            if (colors.isEmpty()) return@mapNotNull null
            AnnotationBuilder.colorProduction(frameIds.cardIid(bound.forgeCardId), colors)
        }

    private fun buildLinkInfoAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        bridge: GameBridge,
    ): List<AnnotationInfo> =
        snap.boundCards.values.flatMap { bound ->
            if (!bound.snapshot.isOnBattlefield) return@flatMap emptyList()
            if (bound.snapshot.chosenType == null && bound.snapshot.chosenColorIds.isEmpty()) return@flatMap emptyList()
            val sourceAbilityGrpId = choiceSourceAbilityGrpId(bound, bridge) ?: return@flatMap emptyList()
            val sourceIid = frameIds.cardIid(bound.forgeCardId)
            buildList {
                val chosenTypeId = bound.snapshot.chosenType?.let { StaticChoiceIds.subtypeIdFor(it) }
                if (chosenTypeId != null) {
                    add(
                        AnnotationBuilder.linkInfoChoice(
                            sourceInstanceId = sourceIid,
                            affectedIds = listOf(6, chosenTypeId),
                            chooseLinkType = "Type",
                            sourceAbilityGrpId = GrpId(sourceAbilityGrpId),
                        ),
                    )
                }
                bound.snapshot.chosenColorIds.firstOrNull()?.let { colorId ->
                    add(
                        AnnotationBuilder.linkInfoChoice(
                            sourceInstanceId = sourceIid,
                            affectedIds = listOf(colorId),
                            chooseLinkType = "Color",
                            sourceAbilityGrpId = GrpId(sourceAbilityGrpId),
                        ),
                    )
                }
            }
        }

    private fun choiceSourceAbilityGrpId(
        bound: BoundCard,
        bridge: GameBridge,
    ): Int? =
        bound.data
            ?.abilityIds
            ?.firstOrNull { (abilityGrpId, _) ->
                bridge.cardRepository.findAbilityInfo(abilityGrpId)?.category == 3
            }?.first

    private fun collectEvidenceAbilityWordPersistentFromPrompt(
        events: List<GameEvent>,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val annotations = mutableListOf<AnnotationInfo>()
        for (seatValue in bridge.allSeatIds().sorted()) {
            val promptBridge = bridge.promptBridge(SeatId(seatValue))
            val context = promptBridge.journal.activeCollectEvidenceCost() ?: continue
            val clearAfterBuild = events.any { it is GameEvent.SpellCast && it.cardId == context.sourceForgeCardId }
            val sourceCard = bridge.findCard(context.sourceForgeCardId)
            val controller = sourceCard?.controller
            val abilityGrpId = sourceCard?.let { collectEvidenceAbilityGrpId(it.name, bridge) } ?: 0
            if (controller != null && abilityGrpId != 0) {
                annotations.add(
                    AnnotationBuilder.abilityWordActive(
                        instanceId = frameIds.cardIid(context.sourceForgeCardId),
                        abilityWordName = "CollectEvidenceCount",
                        value = CardLists.getTotalCMC(controller.getCardsIn(ZoneType.Graveyard)),
                        threshold = context.threshold,
                        abilityGrpId = GrpId(abilityGrpId),
                    ),
                )
            }
            if (clearAfterBuild) {
                promptBridge.journal.clearCollectEvidenceCost()
            }
        }
        return annotations
    }

    private fun collectEvidenceAbilityGrpId(
        cardName: String,
        bridge: GameBridge,
    ): Int {
        val grpId = bridge.cardRepository.findGrpIdByName(cardName) ?: return 0
        val data = bridge.cardRepository.findByGrpId(grpId) ?: return 0
        return data.abilityIds
            .firstOrNull { (abilityGrpId, _) ->
                val info = bridge.cardRepository.findAbilityInfo(abilityGrpId)
                info?.category == COLLECT_EVIDENCE_CATEGORY && info.subCategory == COLLECT_EVIDENCE_SUBCATEGORY
            }?.first ?: 0
    }

    private const val COLLECT_EVIDENCE_CATEGORY = 5
    private const val COLLECT_EVIDENCE_SUBCATEGORY = 29

    private fun decayedCleanupHoldersFromSnap(
        sourceForgeIds: Set<ForgeCardId>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
    ): List<HolderRecord> =
        sourceForgeIds.mapNotNull { sourceForgeId ->
            val bound = snap.boundCards[sourceForgeId]
            val transfer = transferResult.transfers.firstOrNull { it.forgeCardId == sourceForgeId }
            val sourceIid = decayedCleanupSourceIid(sourceForgeId, bound?.snapshot, frameIds, transferResult) ?: return@mapNotNull null
            val cleanupGrpId = decayedCleanupGrpIdForSource(sourceForgeId, snap, bridge, transferResult) ?: return@mapNotNull null
            HolderRecord(
                iid = holderInstanceIdFor(sourceForgeId, bridge).value,
                ownerSeat = bound?.snapshot?.controller?.value ?: transfer?.ownerSeatId ?: return@mapNotNull null,
                objectSourceGrpId = KeywordAbilityIds.DECAYED,
                parentIid = sourceIid,
                cleanupGrpId = cleanupGrpId,
            )
        }

    private fun decayedCleanupSourceIid(
        sourceForgeId: ForgeCardId,
        source: CardSnapshot?,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
    ): Int? {
        if (source?.isOnBattlefield == true) return frameIds.cardIid(sourceForgeId).value
        val transfer =
            transferResult.transfers.firstOrNull {
                it.forgeCardId == sourceForgeId && it.category == TransferCategory.Sacrifice
            }
        return transfer?.origId
    }

    private fun trainingAbilityWordPersistentFromEvents(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val attackEvents = events.filterIsInstance<GameEvent.AttackersDeclared>()
        if (attackEvents.isEmpty()) return emptyList()
        val powerSnap = prev ?: snap

        return attackEvents.flatMap { ev ->
            ev.attackerCardIds.mapNotNull { trainerId ->
                val trainerBound = powerSnap.boundCards[trainerId] ?: snap.boundCards[trainerId] ?: return@mapNotNull null
                if (!hasTrainingKeyword(trainerBound.snapshot.grpId, bridge)) return@mapNotNull null
                val trainerPower = trainerBound.snapshot.netPower ?: return@mapNotNull null
                val partnerId =
                    ev.attackerCardIds.firstOrNull { otherId ->
                        if (otherId == trainerId) return@firstOrNull false
                        val otherBound = powerSnap.boundCards[otherId] ?: snap.boundCards[otherId] ?: return@firstOrNull false
                        (otherBound.snapshot.netPower ?: Int.MIN_VALUE) > trainerPower
                    } ?: return@mapNotNull null

                val trainerIid = frameIds.cardIid(trainerId)
                AnnotationBuilder.abilityWordActive(
                    instanceId = trainerIid,
                    abilityWordName = "Training",
                    affectorId = trainerIid,
                    affectedIds = listOf(frameIds.cardIid(partnerId)),
                )
            }
        }
    }

    private fun hasTrainingKeyword(
        grpId: Int,
        bridge: GameBridge,
    ): Boolean = bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.TRAINING) != null

    private fun tokenSourceForgeId(
        tokenForgeId: ForgeCardId,
        bridge: GameBridge,
    ): ForgeCardId? {
        val tokenCard = bridge.findCard(tokenForgeId) ?: return null
        val sourceCard = tokenCard.tokenSpawningAbility?.hostCard ?: return null
        return ForgeCardId(sourceCard.id)
    }

    private fun mobilizeCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
    ): Int? = snap.boundCards[sourceForgeId]?.mobilizeCleanup

    private fun mobilizeKeywordGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
    ): Int? =
        snap.boundCards[sourceForgeId]
            ?.altCost(KeywordAbilityIds.MOBILIZE)
            ?.abilityGrpId

    private fun holderInstanceIdFor(
        sourceForgeId: ForgeCardId,
        bridge: GameBridge,
    ): InstanceId {
        val holderForge = ForgeCardId(sourceForgeId.value + GameBridge.DELAYED_TRIGGER_HOLDER_FORGE_OFFSET)
        return bridge.getOrAllocInstanceId(holderForge)
    }

    internal fun decayedCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
        bridge: GameBridge,
        transferResult: TransferResult? = null,
    ): Int? {
        snap.boundCards[sourceForgeId]?.decayedCleanup?.let { return it }
        val grpId = transferResult?.transfers?.firstOrNull { it.forgeCardId == sourceForgeId }?.grpId ?: return null
        if (bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.DECAYED) == null) return null
        return bridge.cardRepository.findHiddenTriggeredAbilityGrpId(grpId)
    }
}
