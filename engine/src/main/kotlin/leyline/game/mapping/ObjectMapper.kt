package leyline.game.mapping

import forge.game.card.Card
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.data.CardProtoBuilder
import leyline.game.snapshot.ActivatedAbilityOccurrence
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.CombatRole
import leyline.game.snapshot.EarthbendProjection
import leyline.game.snapshot.EmblemSnapshot
import leyline.game.snapshot.LinkedFaceDescriptor
import leyline.game.snapshot.ParentLinkage
import leyline.game.snapshot.PreparedRole
import leyline.game.state.EffectTracker
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.card.CardType.CoreType as ForgeCoreType

/**
 * Builds [GameObjectInfo] protobuf messages from [CardSnapshot] instances.
 *
 * Static card data (types, colors, abilities, base P/T) comes from [CardProtoBuilder].
 * This mapper adds live game state read from [CardSnapshot]: current P/T, tapped,
 * sickness, damage, loyalty, combat state, and attachment info.
 */
object ObjectMapper {
    data class ActivationProjection(
        val objects: List<GameObjectInfo>,
        val occurrences: Map<ForgeCardId, Map<SeatId, List<ActivatedAbilityOccurrence>>>,
    )

    /** Complete variant occurrence handles only after all ordinary source rows are present. */
    fun planActivationOccurrences(
        objects: List<GameObjectInfo>,
        sources: Map<Int, CardSnapshot>,
    ): ActivationProjection {
        val plans = mutableMapOf<ForgeCardId, Map<SeatId, List<ActivatedAbilityOccurrence>>>()
        val projected =
            objects.map { source ->
                val snapshot = sources[source.instanceId] ?: return@map source
                if (snapshot.activationOccurrences.isEmpty() || source.isFacedown) return@map source
                val rows = source.uniqueAbilitiesList
                val occupied = rows.mapTo(mutableSetOf()) { it.id }
                var nextId = (occupied.maxOrNull() ?: 0) + 1
                val builder = source.toBuilder()
                val origins = source.abilityOriginalCardGrpIdsList
                val plan =
                    snapshot.activationOccurrences.toSortedMap(compareBy { it.value }).mapValues { (_, occurrences) ->
                        val variantDefinitions = occurrences.filter { it.isAlternative }.map { it.definitionId to it.abilityGrpId }.toSet()
                        occurrences.map occurrence@{ occurrence ->
                            if (!occurrence.isAlternative &&
                                (
                                    occurrence.uniqueAbilityId != null ||
                                        occurrence.definitionId to occurrence.abilityGrpId !in variantDefinitions
                                )
                            ) {
                                return@occurrence occurrence
                            }
                            require(occurrence.abilityGrpId != 0) { "Activation occurrence requires a resolved ability definition" }
                            val original =
                                rows.withIndex().singleOrNull { (_, row) ->
                                    row.id == occurrence.uniqueAbilityId && row.grpId == occurrence.abilityGrpId
                                }
                            if (occurrence.uniqueAbilityId != null) requireNotNull(original)
                            while (nextId in occupied) nextId++
                            val id = nextId++
                            occupied.add(id)
                            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(id).setGrpId(occurrence.abilityGrpId))
                            // Sparse effect origins do not establish positional associations.
                            if (original != null && origins.isNotEmpty() && origins.size == rows.size) {
                                builder.addAbilityOriginalCardGrpIds(origins[original.index])
                            }
                            occurrence.copy(uniqueAbilityId = id)
                        }
                    }
                plans[snapshot.forgeCardId] = plan
                builder.build()
            }
        return ActivationProjection(projected, plans)
    }

    /**
     * Build a [GameObjectInfo] for an ability on the stack.
     *
     * [grpId] and [sourceCardGrpId] are independent: [grpId] is the ability row id
     * (e.g. 86 for Cascade), [sourceCardGrpId] is the host permanent's grpId.
     * `cardProto.buildObjectInfo(sourceCardGrpId)` carries the printed card type /
     * color / supertype context the client uses to render the stack tile; the
     * ability's identity rides on the top-level [grpId] field.
     */
    fun buildAbilityObject(
        grpId: Int,
        sourceCardGrpId: Int,
        instanceId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
        parentInstanceId: Int = 0,
    ): GameObjectInfo {
        val builder =
            cardProto
                .buildObjectInfo(sourceCardGrpId)
                .setGrpId(grpId)
                .setInstanceId(instanceId)
                .setType(GameObjectType.Ability)
                .setZoneId(ZoneIds.STACK)
                .setVisibility(Visibility.Public)
                .setOwnerSeatId(ownerSeatId)
                .setControllerSeatId(ownerSeatId)
                .setObjectSourceGrpId(sourceCardGrpId)
        if (parentInstanceId != 0) builder.parentId = parentInstanceId
        return builder.build()
    }

    /**
     * Build a [GameObjectInfo] for a transient `TriggerHolder` object that owns a
     * delayed trigger (Mobilize EOT-sacrifice, exile-and-return, etc.). Lives in
     * Limbo with a fixed `grpId = 5` and `type = GameObjectType.TriggerHolder`,
     * controlled by the source's controller. The same instanceId is the affector
     * for `DelayedTriggerAffectees` and per-token `TemporaryPermanent` annotations.
     * The client renders this object as the side-panel timed-effect indicator —
     * `objectSourceGrpId` (the keyword ability grpId, e.g. 188696 for Mobilize 3)
     * is what carries the icon and tooltip text; `parentId` points at the source
     * card so the client can link the indicator back to its origin.
     */
    fun buildTriggerHolderObject(
        instanceId: Int,
        ownerSeatId: Int,
        objectSourceGrpId: Int = 0,
        parentInstanceId: Int = 0,
        uniqueAbilityGrpId: Int = 0,
        uniqueAbilityId: Int = 0,
    ): GameObjectInfo {
        val builder =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(TRIGGER_HOLDER_GRP_ID)
                .setType(GameObjectType.TriggerHolder)
                .setZoneId(ZoneIds.LIMBO)
                .setVisibility(Visibility.Public)
                .setOwnerSeatId(ownerSeatId)
                .setControllerSeatId(ownerSeatId)
                .setOverlayGrpId(TRIGGER_HOLDER_GRP_ID)
        if (objectSourceGrpId != 0) builder.objectSourceGrpId = objectSourceGrpId
        if (parentInstanceId != 0) builder.parentId = parentInstanceId
        if (uniqueAbilityGrpId != 0) {
            builder.addUniqueAbilities(
                UniqueAbilityInfo
                    .newBuilder()
                    .setId(uniqueAbilityId)
                    .setGrpId(uniqueAbilityGrpId)
                    .build(),
            )
        }
        return builder.build()
    }

    /** Fixed grpId Arena uses for transient trigger-holder objects in Limbo. */
    const val TRIGGER_HOLDER_GRP_ID = 5

    /**
     * Build a [GameObjectInfo] for echo-back GSMs during iterative combat declaration.
     *
     * Echo objects carry NO combat state (no attackState/blockState).
     * Only base card fields are included — P/T, tapped, sickness from [CardSnapshot].
     * Ability rows follow normal projection: [keywordGrants] and [grantedAbilities]
     * that survive the card's ability removals are kept, and removed printed rows are omitted.
     * The client uses the DeclareAttackersReq/DeclareBlockersReq re-prompt
     * (not object state) to track provisional selections.
     */
    fun buildProvisionalCombatObject(
        cardSnap: CardSnapshot,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
        parentLinkage: ParentLinkage? = null,
        keywordGrants: List<EffectTracker.KeywordEntry> = emptyList(),
        grantedAbilities: List<EffectTracker.TrackedGrantedAbility> = emptyList(),
    ): GameObjectInfo {
        val isStackCopySpell = zoneId == ZoneIds.STACK && cardSnap.isCopyToken
        val objType =
            if (cardSnap.isToken && cardSnap.preparedRole !is PreparedRole.Copy && !isStackCopySpell) {
                GameObjectType.Token
            } else {
                // Prepared spells and copied spells on the stack are Forge TOKEN-piece-typed
                // but represent normal castable spells — projected as plain Cards.
                GameObjectType.Card
            }
        val extrinsicKws = cardSnap.survivingKeywordGrants(keywordGrants).mapNotNull { it.resolvedAbilityGrpId }
        return cardProto
            .buildObjectInfo(cardSnap.grpId, extrinsicKeywordGrpIds = extrinsicKws + cardSnap.mergedComponentAbilityGrpIds)
            .setInstanceId(instanceId)
            .setType(objType)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(cardSnap.controller.value)
            .setOthersideGrpId(cardSnap.othersideGrpId)
            .applyFieldsFromSnapshot(cardSnap, parentLinkage) // echo objects carry no combat state
            .apply {
                applyGrantedAbilities(cardSnap.survivingGrantedAbilities(grantedAbilities))
                applyComponentOriginsAndRemovals(cardSnap, cardProto, extrinsicKws.size, hasEarthbend = false)
            }.build()
    }

    /**
     * Build a [GameObjectInfo] for a RevealedCard proxy from a [CardSnapshot].
     *
     * The view object has `type = RevealedCard`, audience-scoped visibility,
     * `zoneId = sourceZoneId` (overlays the source zone, not a synthetic zone),
     * and `viewers = [seatId-of-viewer]`. Mirrors grpId, types, P/T from snapshot.
     */
    fun buildRevealedCardProxy(
        cardSnap: CardSnapshot,
        proxyInstanceId: Int,
        sourceZoneId: Int,
        ownerSeatId: Int,
        viewerSeatId: Int,
        cardProto: CardProtoBuilder,
        parentLinkage: ParentLinkage? = null,
        lookOnly: Boolean = false,
    ): GameObjectInfo =
        cardProto
            .buildObjectInfo(cardSnap.grpId)
            .setInstanceId(proxyInstanceId)
            .setType(GameObjectType.RevealedCard)
            .setZoneId(sourceZoneId)
            .setVisibility(if (lookOnly) Visibility.Private else Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .addViewers(viewerSeatId)
            .applyFieldsFromSnapshot(cardSnap, parentLinkage)
            .build()

    /**
     * Build a [GameObjectInfo] from a [CardSnapshot].
     *
     * [visibility] controls hand (Private) vs graveyard/battlefield/exile (Public).
     * controllerSeatId is taken from [cardSnap.controller].
     * [keywordSnapshot] is passed for shared-zone cards that carry extrinsic keyword grants.
     *
     * Combat state is applied when [cardSnap.combatRole] is non-null (populated by
     * [SnapshotCapture] for battlefield creatures in combat).
     */
    fun buildFromSnapshot(
        cardSnap: CardSnapshot,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
        visibility: Visibility = Visibility.Private,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
        parentLinkage: ParentLinkage? = null,
        earthbend: EarthbendProjection? = null,
        grantedAbilitySnapshot: Map<Int, List<EffectTracker.TrackedGrantedAbility>> = emptyMap(),
    ): GameObjectInfo {
        cardSnap.emblem?.let { emblem ->
            return GameObjectInfo
                .newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(EmblemSnapshot.GRP_ID)
                .setOverlayGrpId(EmblemSnapshot.GRP_ID)
                .setType(GameObjectType.Emblem)
                .setZoneId(zoneId)
                .setVisibility(Visibility.Public)
                .setOwnerSeatId(cardSnap.owner.value)
                .setControllerSeatId(cardSnap.controller.value)
                .setObjectSourceGrpId(emblem.sourceGrpId)
                .setParentId(emblem.parentInstanceId)
                .setName(1)
                .addAllUniqueAbilities(
                    emblem.abilityGrpIds.mapIndexed { index, grpId ->
                        UniqueAbilityInfo
                            .newBuilder()
                            .setId(50 + index)
                            .setGrpId(grpId)
                            .build()
                    },
                ).build()
        }
        val extrinsicKws = cardSnap.survivingKeywordGrants(keywordSnapshot[instanceId].orEmpty()).mapNotNull { it.resolvedAbilityGrpId }
        // Face-down objects conceal printed identity while retaining
        // mechanic-specific intrinsic abilities and established keyword grants.
        if (cardSnap.faceDownKind != null || cardSnap.isFaceDown && (cardSnap.isOnBattlefield || zoneId == ZoneIds.STACK)) {
            val hasIntrinsicWard =
                when (cardSnap.faceDownKind) {
                    leyline.game.snapshot.FaceDownKind.Cloak, leyline.game.snapshot.FaceDownKind.Disguise -> true
                    leyline.game.snapshot.FaceDownKind.ManifestDread, null -> false
                }
            return cardProto
                .buildFaceDownObjectInfo(cardSnap.grpId, hasIntrinsicWard, extrinsicKws)
                .setInstanceId(instanceId)
                .setType(GameObjectType.Card)
                .setZoneId(zoneId)
                .setVisibility(Visibility.Private)
                .setOwnerSeatId(ownerSeatId)
                .setControllerSeatId(cardSnap.controller.value)
                .addViewers(cardSnap.controller.value)
                .apply {
                    applyFaceDownCharacteristics(cardSnap)
                    cardSnap.combatRole?.let { applyCombatFromSnapshot(it) }
                    applyGrantedAbilities(grantedAbilitySnapshot[instanceId].orEmpty())
                    if (cardSnap.isOnBattlefield) {
                        setIsTapped(cardSnap.tapped)
                        setHasSummoningSickness(cardSnap.hasSickness)
                        if (cardSnap.damage > 0) setDamage(cardSnap.damage)
                    }
                }.build()
        }

        val isStackCopySpell = zoneId == ZoneIds.STACK && cardSnap.isCopyToken
        val objType =
            if (cardSnap.isToken && cardSnap.preparedRole !is PreparedRole.Copy && !isStackCopySpell) {
                GameObjectType.Token
            } else {
                // Prepared spells and copied spells on the stack are Forge TOKEN-piece-typed
                // but represent normal castable spells — projected as plain Cards.
                GameObjectType.Card
            }
        val extraAbilityGrpIds = extrinsicKws + cardSnap.mergedComponentAbilityGrpIds
        val builder =
            cardProto
                .buildObjectInfo(cardSnap.grpId, extrinsicKeywordGrpIds = extraAbilityGrpIds)
                .setInstanceId(instanceId)
                .setType(objType)
                .setZoneId(zoneId)
                .setVisibility(visibility)
                .setOwnerSeatId(ownerSeatId)
                .setControllerSeatId(cardSnap.controller.value)
                .setOthersideGrpId(cardSnap.othersideGrpId)
                .applyFieldsFromSnapshot(cardSnap, parentLinkage)
        earthbend?.let { earthbend ->
            if (builder.uniqueAbilitiesList.none { it.grpId == earthbend.hasteAbilityGrpId }) {
                builder.addUniqueAbilities(
                    UniqueAbilityInfo
                        .newBuilder()
                        .setId(earthbend.uniqueAbilityId)
                        .setGrpId(earthbend.hasteAbilityGrpId),
                )
            }
            builder.addAbilityOriginalCardGrpIds(earthbend.sourceCardGrpId)
        }
        builder.applyGrantedAbilities(cardSnap.survivingGrantedAbilities(grantedAbilitySnapshot[instanceId].orEmpty()))
        builder.applyComponentOriginsAndRemovals(cardSnap, cardProto, extrinsicKws.size, hasEarthbend = earthbend != null)
        return builder.build()
    }

    private fun GameObjectInfo.Builder.applyGrantedAbilities(grants: List<EffectTracker.TrackedGrantedAbility>) {
        grants.forEach { granted ->
            if (uniqueAbilitiesList.none { it.id == granted.uniqueAbilityId }) {
                addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(granted.uniqueAbilityId).setGrpId(granted.abilityGrpId))
            }
        }
    }

    /** Keyword grants not hidden by a later ability-removing effect (CR 613.7) or a can't-have effect. */
    private fun CardSnapshot.survivingKeywordGrants(grants: List<EffectTracker.KeywordEntry>): List<EffectTracker.KeywordEntry> =
        grants.filterNot { grant -> abilityRemovals.any { it.suppressesGrant(grant.timestamp, grant.keyword) } }

    /** Granted activated abilities not hidden by a later ability-removing effect. */
    private fun CardSnapshot.survivingGrantedAbilities(
        grants: List<EffectTracker.TrackedGrantedAbility>,
    ): List<EffectTracker.TrackedGrantedAbility> =
        grants.filterNot { grant -> abilityRemovals.any { it.suppressesGrant(grant.fingerprint.timestamp, keyword = null) } }

    /**
     * Add merged-permanent origins for the printed, keyword-grant and component rows, then omit rows hidden by
     * ability-removing effects. Rows are laid out printed, then [extrinsicKeywordRows] keyword grants, then components.
     */
    private fun GameObjectInfo.Builder.applyComponentOriginsAndRemovals(
        cardSnap: CardSnapshot,
        cardProto: CardProtoBuilder,
        extrinsicKeywordRows: Int,
        hasEarthbend: Boolean,
    ) {
        val printedRows = cardProto.staticAbilityGrpIds(cardSnap.grpId).size
        if (cardSnap.isMergedPermanent) {
            addAllAbilityOriginalCardGrpIds(
                List(printedRows + extrinsicKeywordRows) { cardSnap.grpId } + cardSnap.mergedComponentAbilityOriginalCardGrpIds,
            )
        }
        omitRemovedAbilities(
            cardSnap,
            printedRows = printedRows,
            componentRows = extrinsicKeywordRows until extrinsicKeywordRows + cardSnap.mergedComponentAbilityGrpIds.size,
            originsLeadRows = cardSnap.isMergedPermanent && !hasEarthbend,
        )
    }

    /**
     * Omit printed and component rows hidden by ability-removing effects, keeping recipient-local IDs of the
     * surviving rows. Grants are filtered by timestamp before they are added. [componentRows] is relative to the
     * first row after the printed rows. When [originsLeadRows] is set, origin `i` belongs to row `i` and removed
     * rows drop their origin; otherwise origins pair with later rows (an earthbend Haste row) and stay unchanged.
     */
    private fun GameObjectInfo.Builder.omitRemovedAbilities(
        snapshot: CardSnapshot,
        printedRows: Int,
        componentRows: IntRange = IntRange.EMPTY,
        originsLeadRows: Boolean = false,
    ) {
        val removed = snapshot.abilityRemovals.flatMapTo(mutableSetOf()) { it.removedAbilityGrpIds }
        if (removed.isEmpty()) return
        val abilities = uniqueAbilitiesList.toList()
        val dropped =
            abilities.indices.filterTo(mutableSetOf()) { index ->
                (index < printedRows || index - printedRows in componentRows) && abilities[index].grpId in removed
            }
        if (dropped.isEmpty()) return
        clearUniqueAbilities().addAllUniqueAbilities(abilities.filterIndexed { index, _ -> index !in dropped })
        if (originsLeadRows) {
            val origins = abilityOriginalCardGrpIdsList.toList()
            clearAbilityOriginalCardGrpIds().addAllAbilityOriginalCardGrpIds(origins.filterIndexed { index, _ -> index !in dropped })
        }
    }

    fun buildDisturbBackObject(
        cardSnap: CardSnapshot,
        instanceId: Int,
        parentInstanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
        visibility: Visibility,
    ): GameObjectInfo =
        cardProto
            .buildObjectInfo(cardSnap.othersideGrpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.DisturbBack)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(cardSnap.controller.value)
            .setParentId(parentInstanceId)
            .setOthersideGrpId(cardSnap.grpId)
            .build()

    /** Build a secondary-face companion attached to [parent]. */
    fun buildLinkedFaceObject(
        face: LinkedFaceDescriptor,
        instanceId: Int,
        parent: GameObjectInfo,
        cardProto: CardProtoBuilder,
    ): GameObjectInfo =
        cardProto
            .buildObjectInfo(face.grpId)
            .setInstanceId(instanceId)
            .setType(face.role.objectType)
            .setZoneId(parent.zoneId)
            .setVisibility(parent.visibility)
            .setOwnerSeatId(parent.ownerSeatId)
            .setControllerSeatId(parent.controllerSeatId)
            .setParentId(parent.instanceId)
            .addAllViewers(
                parent.viewersList.ifEmpty {
                    listOf(parent.ownerSeatId)
                        .takeIf { parent.visibility == Visibility.Private && !(parent.isFacedown && parent.zoneId == ZoneIds.EXILE) }
                        .orEmpty()
                },
            ).build()

    /**
     * Apply live game state from [cardSnap] onto a [GameObjectInfo.Builder].
     *
     * Overlays: live card types, P/T, tapped, sickness, damage, loyalty, combat, attachment.
     */
    private fun GameObjectInfo.Builder.applyFieldsFromSnapshot(
        cardSnap: CardSnapshot,
        parentLinkage: ParentLinkage? = null,
    ): GameObjectInfo.Builder {
        // Live card types — overlay when they differ from DB (same logic as overlayCardTypes)
        overlayCardTypesFromSnapshot(cardSnap)

        // Live P/T — set for all creatures regardless of zone
        val isCreature = cardSnap.liveCardTypeNumbers.contains(CardType.Creature.number)
        if (isCreature) {
            cardSnap.netPower?.let { setPower(Int32Value.newBuilder().setValue(it)) }
            cardSnap.netToughness?.let { setToughness(Int32Value.newBuilder().setValue(it)) }
        }

        // Permanent state — battlefield only
        if (cardSnap.isOnBattlefield) {
            setIsTapped(cardSnap.tapped)
            if (isCreature) {
                setHasSummoningSickness(cardSnap.hasSickness)
                if (cardSnap.damage > 0) setDamage(cardSnap.damage)
            }
            val isPlaneswalker = cardSnap.liveCardTypeNumbers.contains(CardType.Planeswalker.number)
            if (isPlaneswalker) {
                setLoyalty(UInt32Value.newBuilder().setValue(cardSnap.currentLoyalty))
            }
        }

        // Copy token identity
        if (cardSnap.isCopyToken) {
            setIsCopy(true)
            setObjectSourceGrpId(this.grpId)
        }
        if (cardSnap.tokenSourceCardGrpId != 0) {
            setObjectSourceGrpId(cardSnap.tokenSourceCardGrpId)
        }
        if (cardSnap.tokenParentAbilityInstanceId != 0) {
            setParentId(cardSnap.tokenParentAbilityInstanceId)
        }

        // Prepared-spell exile copies project as Card with isCopy=true. The
        // parentId is set below via parentLinkage; the source can be null mid-
        // cast, in which case parentLinkage is also null and parentId is
        // omitted while isCopy still applies.
        if (cardSnap.preparedRole is PreparedRole.Copy) {
            setIsCopy(true)
        }

        when (parentLinkage) {
            is ParentLinkage.PreparedCopy -> setParentId(parentLinkage.parentInstanceId)
            is ParentLinkage.AttachedTo -> setParentId(parentLinkage.parentInstanceId)
            null -> {}
        }

        // Combat state
        if (cardSnap.combatRole != null) {
            applyCombatFromSnapshot(cardSnap.combatRole)
        }

        return this
    }

    /** Apply attackState/blockState and attackInfo/blockInfo from a [CombatRole]. */
    private fun GameObjectInfo.Builder.applyCombatFromSnapshot(role: CombatRole) {
        when (role) {
            is CombatRole.Attacker -> {
                setAttackState(AttackState.Attacking)
                if (role.targetInstanceId > 0) {
                    setAttackInfo(AttackInfo.newBuilder().setTargetId(role.targetInstanceId))
                }
                when (role.isBlocked) {
                    true -> setBlockState(BlockState.Blocked)
                    false -> setBlockState(BlockState.Unblocked)
                    null -> Unit
                }
            }
            is CombatRole.Blocker -> {
                setBlockState(BlockState.Blocking)
                if (role.attackerInstanceIds.isNotEmpty()) {
                    setBlockInfo(
                        BlockInfo.newBuilder().apply {
                            for (id in role.attackerInstanceIds) addAttackerIds(id)
                        },
                    )
                }
            }
        }
    }

    private fun GameObjectInfo.Builder.applyFaceDownCharacteristics(cardSnap: CardSnapshot) {
        overlayCardTypesFromSnapshot(cardSnap)
        cardSnap.liveSubtypeNumbers.forEach { number -> CardProtoBuilder.subtypesByNumber[number]?.let { addSubtypes(it) } }
        clearPower()
        clearToughness()
        cardSnap.netPower?.let { setPower(Int32Value.newBuilder().setValue(it)) }
        cardSnap.netToughness?.let { setToughness(Int32Value.newBuilder().setValue(it)) }
        if (cardSnap.basicLandManaAbilityGrpId != 0) {
            addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(50 + uniqueAbilitiesCount).setGrpId(cardSnap.basicLandManaAbilityGrpId))
        }
    }

    /**
     * Overlay live card types from [CardSnapshot.liveCardTypeNumbers].
     * Rebuilds only when the live set differs from the DB-provided set.
     */
    private fun GameObjectInfo.Builder.overlayCardTypesFromSnapshot(cardSnap: CardSnapshot) {
        val liveNums = cardSnap.liveCardTypeNumbers.toSortedSet()
        val dbNums = cardTypesList.map { it.number }.toSortedSet()
        if (liveNums == dbNums) return
        clearCardTypes()
        for (num in liveNums) {
            CardType.forNumber(num)?.let { addCardTypes(it) }
        }
    }

    /** Forge CoreType → proto CardType mapping. Shared with [leyline.game.snapshot.SnapshotCapture]. */
    internal val coreTypeToProto: Map<ForgeCoreType, CardType> =
        mapOf(
            ForgeCoreType.Artifact to CardType.Artifact_a80b,
            ForgeCoreType.Creature to CardType.Creature,
            ForgeCoreType.Enchantment to CardType.Enchantment,
            ForgeCoreType.Instant to CardType.Instant,
            ForgeCoreType.Land to CardType.Land_a80b,
            ForgeCoreType.Planeswalker to CardType.Planeswalker,
            ForgeCoreType.Sorcery to CardType.Sorcery,
            ForgeCoreType.Kindred to CardType.Kindred,
            ForgeCoreType.Battle to CardType.Battle,
        )
}
