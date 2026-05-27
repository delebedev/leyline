package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PreparedRole
import leyline.game.state.GameBridge
import leyline.game.state.HolderRecord
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Snap- or event-derived persistent annotation inputs for one GSM build. */
internal data class PersistentFeedSet(
    val qualification: List<AnnotationInfo> = emptyList(),
    val temporaryPermanent: List<AnnotationInfo> = emptyList(),
    val delayedTriggerAffectees: List<AnnotationInfo> = emptyList(),
    val abilityWord: List<AnnotationInfo> = emptyList(),
    val preparedDesignation: List<AnnotationInfo> = emptyList(),
    val plottedDesignation: List<AnnotationInfo> = emptyList(),
    val saddledDesignation: List<AnnotationInfo> = emptyList(),
    val commanderDesignation: List<AnnotationInfo> = emptyList(),
    val leftUnlockedDesignation: List<AnnotationInfo> = emptyList(),
    val rightUnlockedDesignation: List<AnnotationInfo> = emptyList(),
    val dayNightDesignation: List<AnnotationInfo> = emptyList(),
    val faceDownDisguise: List<AnnotationInfo> = emptyList(),
    val colorProduction: List<AnnotationInfo> = emptyList(),
)

internal data class PersistentFeedBuildResult(
    val feeds: PersistentFeedSet,
    val currentHolders: List<HolderRecord>,
)

internal object PersistentFeedBuilder {
    @Suppress("LongMethod")
    fun build(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        decayedCleanupSourcesThisGsm: Set<ForgeCardId>,
        transferResult: TransferResult,
    ): PersistentFeedBuildResult {
        val qualificationPersistentFromSnap =
            snap.objects.values
                .filter { it.isOnAdventure }
                .map { AnnotationBuilder.qualification(instanceId = frameIds.cardIid(it.forgeCardId)) } +
                CombatQualificationScanner.scan(snap, bridge, frameIds)
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
        val temporaryPermanentPersistentFromSnap =
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
        val delayedTriggerAffecteesFromSnap =
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
        val abilityWordPersistentFromSnap =
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
            }
        val trainingAbilityWordPersistentFromEvents = trainingAbilityWordPersistentFromEvents(events, snap, prev, bridge, frameIds)
        val preparedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    val source = bound.designations.prepared as? PreparedRole.Source ?: return@mapNotNull null
                    AnnotationBuilder.preparedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                        preparedCopyInstanceId = bridge.getOrAllocInstanceId(source.copyForgeCardId),
                    )
                }
        val plottedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isPlotted) return@mapNotNull null
                    AnnotationBuilder.plottedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }
        val saddledDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isSaddled) return@mapNotNull null
                    AnnotationBuilder.saddledDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }
        val commanderDesignationPersistentFromSnap =
            snap.boundCards.values
                .filter { it.designations.isCommander && it.snapshot.grpId > 0 }
                .flatMap { bound ->
                    val iid = frameIds.cardIid(bound.forgeCardId)
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
        val leftUnlockedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isLeftDoorUnlocked) return@mapNotNull null
                    AnnotationBuilder.leftUnlockedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }
        val rightUnlockedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isRightDoorUnlocked) return@mapNotNull null
                    AnnotationBuilder.rightUnlockedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }
        val faceDownDisguisePersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.snapshot.isFaceDownDisguise) return@mapNotNull null
                    AnnotationBuilder.faceDownPersistent(
                        instanceId = frameIds.cardIid(bound.forgeCardId),
                        reason = AnnotationConstants.FACEDOWN_REASON_DISGUISE,
                        abilityGrpId = GrpId(KeywordAbilityIds.DISGUISE),
                    )
                }
        val dayNightDesignationPersistentFromSnap: List<AnnotationInfo> =
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
        val colorProductionPersistentFromSnap = buildColorProductionAnnotations(snap, frameIds)
        return PersistentFeedBuildResult(
            feeds =
                PersistentFeedSet(
                    qualification = qualificationPersistentFromSnap,
                    temporaryPermanent = temporaryPermanentPersistentFromSnap,
                    delayedTriggerAffectees = delayedTriggerAffecteesFromSnap,
                    abilityWord = abilityWordPersistentFromSnap + trainingAbilityWordPersistentFromEvents,
                    preparedDesignation = preparedDesignationPersistentFromSnap,
                    plottedDesignation = plottedDesignationPersistentFromSnap,
                    saddledDesignation = saddledDesignationPersistentFromSnap,
                    commanderDesignation = commanderDesignationPersistentFromSnap,
                    leftUnlockedDesignation = leftUnlockedDesignationPersistentFromSnap,
                    rightUnlockedDesignation = rightUnlockedDesignationPersistentFromSnap,
                    dayNightDesignation = dayNightDesignationPersistentFromSnap,
                    faceDownDisguise = faceDownDisguisePersistentFromSnap,
                    colorProduction = colorProductionPersistentFromSnap,
                ),
            currentHolders = currentHolders,
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
