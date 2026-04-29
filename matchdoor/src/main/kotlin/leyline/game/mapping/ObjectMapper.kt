package leyline.game.mapping

import forge.game.card.Card
import leyline.game.codes.KeywordGrpIds
import leyline.game.data.CardProtoBuilder
import leyline.game.data.CardRepository
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.CombatRole
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
    /** Offset added to source card IDs for stack ability instance IDs. */
    internal const val STACK_ABILITY_ID_OFFSET = 100_000

    /**
     * Build a [GameObjectInfo] for an ability on the stack.
     */
    fun buildAbilityObject(
        grpId: Int,
        instanceId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
    ): GameObjectInfo =
        cardProto
            .buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Ability)
            .setZoneId(ZoneIds.STACK)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .setObjectSourceGrpId(grpId)
            .build()

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
     * The client uses the DeclareAttackersReq/DeclareBlockersReq re-prompt
     * (not object state) to track provisional selections.
     */
    fun buildProvisionalCombatObject(
        cardSnap: CardSnapshot,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        cardProto: CardProtoBuilder,
    ): GameObjectInfo {
        val objType =
            if (cardSnap.isToken && cardSnap.preparedRole !is PreparedRole.Copy) {
                GameObjectType.Token
            } else {
                // Prepared-spell copies are Forge TOKEN-piece-typed but represent a
                // normal castable spell — projected as plain Cards.
                GameObjectType.Card
            }
        return cardProto
            .buildObjectInfo(cardSnap.grpId)
            .setInstanceId(instanceId)
            .setType(objType)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(cardSnap.controller.value)
            .setOthersideGrpId(cardSnap.othersideGrpId)
            .applyFieldsFromSnapshot(cardSnap) // echo objects carry no combat state (combatRole=null in snap)
            .build()
    }

    /**
     * Build a [GameObjectInfo] for a RevealedCard proxy from a [CardSnapshot].
     *
     * Proxy has `type = RevealedCard`, `visibility = Public`,
     * `zoneId = handZoneId` (overlays the hand zone, NOT the Revealed zone),
     * and `viewers = [seatId-of-viewer]`. Mirrors grpId, types, P/T from snapshot.
     */
    fun buildRevealedCardProxy(
        cardSnap: CardSnapshot,
        proxyInstanceId: Int,
        handZoneId: Int,
        ownerSeatId: Int,
        viewerSeatId: Int,
        cardProto: CardProtoBuilder,
    ): GameObjectInfo =
        cardProto
            .buildObjectInfo(cardSnap.grpId)
            .setInstanceId(proxyInstanceId)
            .setType(GameObjectType.RevealedCard)
            .setZoneId(handZoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .addViewers(viewerSeatId)
            .applyFieldsFromSnapshot(cardSnap)
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
    ): GameObjectInfo {
        val objType =
            if (cardSnap.isToken && cardSnap.preparedRole !is PreparedRole.Copy) {
                GameObjectType.Token
            } else {
                // Prepared-spell copies are Forge TOKEN-piece-typed but represent a
                // normal castable spell — projected as plain Cards.
                GameObjectType.Card
            }
        val extrinsicKws =
            keywordSnapshot[instanceId]
                ?.mapNotNull { KeywordGrpIds.forKeyword(it.keyword) }
                ?: emptyList()
        return cardProto
            .buildObjectInfo(cardSnap.grpId, extrinsicKeywordGrpIds = extrinsicKws)
            .setInstanceId(instanceId)
            .setType(objType)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(cardSnap.controller.value)
            .setOthersideGrpId(cardSnap.othersideGrpId)
            .applyFieldsFromSnapshot(cardSnap)
            .build()
    }

    /**
     * Apply live game state from [cardSnap] onto a [GameObjectInfo.Builder].
     *
     * Overlays: live card types, P/T, tapped, sickness, damage, loyalty, combat, attachment.
     */
    private fun GameObjectInfo.Builder.applyFieldsFromSnapshot(cardSnap: CardSnapshot): GameObjectInfo.Builder {
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

        // Attachment (Auras, Equipment) — pre-resolved instanceId from snapshot.
        // Set FIRST so the prepared-copy branch below has final say if both apply
        // (theoretical: a continuous effect attaching the exile copy to something).
        // The protocol semantic for "prepared exile copy that's also attached" is
        // unspecified; preferring the prepared parentId keeps the cast-from-exile
        // linkage intact for the client.
        cardSnap.attachedToInstanceId?.let { setParentId(it) }

        // Prepared-spell exile copy — projects as a Card parented to the prepared
        // source creature. GameObject form: isCopy=true, parentId=<creature iid>,
        // no objectSourceGrpId (that field is for engine-spawned tokens). The
        // source instanceId can be null mid-cast; isCopy still applies, parentId
        // is omitted in that case.
        if (cardSnap.preparedRole is PreparedRole.Copy) {
            setIsCopy(true)
            cardSnap.preparedCopySourceInstanceId?.let { setParentId(it) }
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

    /** Resolve the other face's grpId for DFC cards. Returns 0 for non-DFC.
     *
     *  Scope: **transform DFCs + meld pairs only** — Forge's `Card.isDoubleFaced`
     *  predicate is `isTransformable() || isMeldable()`. MDFC, Adventure, Split,
     *  Flip, Saga, Battle, and Room cards do NOT enter this branch; their grpId
     *  resolution goes through [leyline.game.snapshot.GrpIdResolver]'s
     *  primary/any-face fallback chain.
     *
     *  Back-face cards (Luminous Phantom, Waildrifter, etc.) have IsPrimaryCard=0
     *  in the Arena DB, so [findGrpIdByName]'s primary-only filter misses them.
     *  Fall back to [findGrpIdByNameAnyFace] which lifts that filter. */
    internal fun resolveOthersideGrpId(
        card: Card,
        cards: CardRepository,
    ): Int {
        if (!card.isDoubleFaced) return 0
        val otherStateName =
            if (card.currentState.stateName == forge.card.CardStateName.Backside) {
                forge.card.CardStateName.Original
            } else {
                forge.card.CardStateName.Backside
            }
        val otherState = card.getState(otherStateName) ?: return 0
        return cards.findGrpIdByName(otherState.name)
            ?: cards.findGrpIdByNameAnyFace(otherState.name)
            ?: 0
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
