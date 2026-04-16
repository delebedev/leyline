package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Builds client-format [AnnotationInfo] protos for [GameStateMessage] bundles.
 *
 * client annotations are the **semantic layer** on top of raw game state diffs.
 * The client has two independent parser families that consume them:
 * - **State parsers** (~58 classes): mutate card/game state fields directly
 *   (P/T, counters, abilities, designations, attachments)
 * - **Event parsers** (~46 classes): produce [GameRulesEvent] objects that drive
 *   the animation/sound pipeline (zone transfers, damage flash, life counter)
 *
 * Some annotation types fire **both** parsers (e.g. ResolutionStart, Shuffle,
 * DieRoll). Each builder method here maps to one [AnnotationType] enum value
 * and matches the expected detail key names, value types, and shape.
 * Detail keys are case-sensitive; the client throws on missing required fields.
 *
 * **Ordering contract:** [objectIdChanged] must appear before [zoneTransfer]
 * for the same card. The client's ZoneTransfer event parser expects the new
 * instanceId to be resolvable — the ObjectIdChanged state parser stores the
 * old→new mapping in `newIdToOldIdMap` which must run first.
 *
 * **Organization by tier** (matching annotation-variance-analysis priority):
 * - Transfer/lifecycle: [zoneTransfer], [objectIdChanged], [resolutionStart],
 *   [abilityInstanceCreated] — core zone movement and stack lifecycle
 * - Combat: [damageDealt], [modifiedLife], [syntheticEvent] — damage chain
 * - Tier 1 (game state): [counter], [addAbility], [layeredEffect],
 *   [designation] — affect correctness if missing
 * - Tier 2 (visual fidelity): [colorProduction], [targetSpec],
 *   [powerToughnessModCreated], [attachmentCreated] — affect client UX
 *
 * Authoritative client parser reference: from protocol analysis (annotation registry)
 *
 * @see ZoneTransferDetector for zone transfer detection
 * @see TransferCategoryResolver for event-to-category resolution
 * @see TransferAnnotations for transfer-stage annotation generation
 * @see CombatAnnotations for combat-stage annotations
 * @see MechanicAnnotations for mechanic and effect annotations
 */
@Suppress("LargeClass")
object AnnotationBuilder {

    fun zoneTransfer(
        instanceId: Int,
        srcZoneId: Int,
        destZoneId: Int,
        category: String,
        actingSeatId: Int = 0,
        affectorId: Int = 0,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ZoneTransfer_af5a)
        .apply {
            // affectorId takes precedence (ability instance); fall back to actingSeatId (player seat)
            val aff = if (affectorId != 0) affectorId else actingSeatId
            if (aff != 0) setAffectorId(aff)
        }
        .addAffectedIds(instanceId)
        .addDetails(int32Detail(DetailKeys.ZONE_SRC, srcZoneId))
        .addDetails(int32Detail(DetailKeys.ZONE_DEST, destZoneId))
        .addDetails(typedStringDetail(DetailKeys.CATEGORY, category))
        .build()

    /** Spell/ability begins resolving. Client uses this to start resolution animation. */
    fun resolutionStart(instanceId: Int, grpId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ResolutionStart)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
            .build()

    /** A new turn started. Client uses this to reset turn-scoped state.
     *  [activeSeat] = the active player's seat for the new turn. */
    fun newTurnStarted(activeSeat: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.NewTurnStarted)
            .setAffectorId(activeSeat)
            .addAffectedIds(activeSeat)
            .build()

    /** Phase/step changed. Client uses this to animate the phase tracker.
     *  [activeSeat] = active player seat, [phase]/[step] = proto enum ordinals. */
    fun phaseOrStepModified(activeSeat: Int, phase: Int, step: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PhaseOrStepModified)
            .addAffectedIds(activeSeat)
            .addDetails(int32Detail(DetailKeys.PHASE, phase))
            .addDetails(int32Detail(DetailKeys.STEP, step))
            .build()

    /** Card's instanceId changed (e.g. zone move creates new object).
     *  [affectorId] = ability instance that caused the change (0 = unset). */
    fun objectIdChanged(origId: Int, newId: Int, affectorId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ObjectIdChanged)
            .apply { if (affectorId != 0) setAffectorId(affectorId) }
            .addAffectedIds(origId)
            .addDetails(int32Detail(DetailKeys.ORIG_ID, origId))
            .addDetails(int32Detail(DetailKeys.NEW_ID, newId))
            .build()

    /**
     * Ties a game state change back to a player interaction.
     * [seatId] = acting player's seat (affectorId).
     * [actionType] = client [ActionType] enum (Cast, Play_add3, ActivateMana, CastAdventure, …).
     * [abilityGrpId] = ability group ID (0 for land play).
     * [alternativeGrpId] = alt-cost ability grpId (Madness, Flashback, Warp, Cycling, etc.).
     *   Pass 0 (default) when the spell was cast for its regular cost. When non-zero, the
     *   client renders the cast as having gone through an alternate cost path.
     */
    fun userActionTaken(
        instanceId: Int,
        seatId: Int,
        actionType: ActionType = ActionType.None_add3,
        abilityGrpId: Int = 0,
        alternativeGrpId: Int = 0,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.UserActionTaken)
            .setAffectorId(seatId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.ACTION_TYPE, actionType.number))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId))
            .apply {
                if (alternativeGrpId != 0) {
                    addDetails(int32Detail(DetailKeys.ALTERNATIVE_GRP_ID, alternativeGrpId))
                }
            }
            .build()

    /**
     * CastingTimeOption — persistent annotation marking how a spell on the stack was cast.
     *
     * Most common shape (and the one used by the alt-cost mechanic family):
     * **CastThroughAbility** — spell cast via an alternate cost ability
     * (Madness, Flashback, Warp, Cycling, Impending). [alternateCostGrpId] and
     * [castAbilityGrpId] both carry the alt-cost ability's grpId.
     *
     * Persistent while the spell is on the stack; deleted via
     * `diffDeletedPersistentAnnotationIds` when the spell resolves or leaves the stack.
     *
     * Other [CastingTimeOptionType] values (Kicker, AdditionalCost, ChooseX_a7b4, …) exist but
     * are not exercised by alt-cost mechanics.
     *
     * [stackInstanceId] = the spell instance currently on the stack (affector AND affected,
     *   since the annotation is self-attached).
     * [alternateCostGrpId] = the alt-cost ability grpId.
     * [castAbilityGrpId] = same as [alternateCostGrpId] for CastThroughAbility.
     */
    fun castingTimeOption(
        stackInstanceId: Int,
        type: CastingTimeOptionType,
        alternateCostGrpId: Int,
        castAbilityGrpId: Int = alternateCostGrpId,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId)
            .addAffectedIds(stackInstanceId)
            .addDetails(int32Detail(DetailKeys.TYPE, type.number))
            .addDetails(int32Detail(DetailKeys.ALTERNATE_COST_GRP_ID, alternateCostGrpId))
            .addDetails(int32Detail(DetailKeys.CAST_ABILITY_GRP_ID, castAbilityGrpId))
            .build()

    /**
     * Mana was spent to pay for a spell/ability.
     * [spellInstanceId] = the spell/ability instance that consumed the mana (affectedIds).
     * [landInstanceId] = the land (or mana source) that produced the mana (affectorId).
     * [manaId] = mana payment tracking ID (protocol uses sequential assignment here).
     * [color] = mana color as int bitmask (e.g. 2 = blue), matching the client wire format.
     * When mana tracking is not available, pass defaults (0, 0, 0).
     */
    fun manaPaid(spellInstanceId: Int, landInstanceId: Int, manaId: Int = 0, color: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ManaPaid)
            .setAffectorId(landInstanceId)
            .addAffectedIds(spellInstanceId)
            .addDetails(int32Detail(DetailKeys.ID, manaId))
            .addDetails(int32Detail(DetailKeys.COLOR, color))
            .build()

    /**
     * Permanent tapped or untapped (e.g. tapping land for mana).
     * [permanentId] = the permanent being tapped (affectedIds).
     * [abilityId] = the ability instance that caused the tap (affectorId).
     *   Client expects a transient mana ability id; we approximate with the spell id.
     */
    fun tappedUntappedPermanent(permanentId: Int, abilityId: Int, tapped: Boolean = true): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TappedUntappedPermanent)
            .setAffectorId(abilityId)
            .addAffectedIds(permanentId)
            .addDetails(int32Detail(DetailKeys.TAPPED, if (tapped) 1 else 0))
            .build()

    /**
     * Ability instance created on the stack.
     * [abilityInstanceId] = the ability/spell instance being created (affectedIds).
     * [affectorId] = the land or permanent that triggered this ability creation (e.g. tapping a land for mana).
     *   Pass 0 when not applicable (e.g. casting a spell from hand).
     * [sourceZoneId] = zone the ability/spell came from (e.g. Hand=31).
     * Client expects this field; client may use it for animation origin.
     */
    fun abilityInstanceCreated(abilityInstanceId: Int, affectorId: Int = 0, sourceZoneId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AbilityInstanceCreated)
            .setAffectorId(affectorId)
            .addAffectedIds(abilityInstanceId)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZoneId))
            .build()

    /**
     * Ability instance deleted (e.g. hand's play ability consumed after casting,
     * or a mana ability instance cleared after payment).
     * [abilityInstanceId] = the ability/spell instance being removed (affectedIds).
     * [affectorId] = the permanent that owns the ability, when applicable (e.g. tapped land).
     *   Pass 0 when not applicable.
     */
    fun abilityInstanceDeleted(abilityInstanceId: Int, affectorId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AbilityInstanceDeleted)
            .setAffectorId(affectorId)
            .addAffectedIds(abilityInstanceId)
            .build()

    /** Spell/ability done resolving. Client uses this to finalize stack→battlefield move. */
    fun resolutionComplete(instanceId: Int, grpId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ResolutionComplete)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
            .build()

    /**
     * Combat damage dealt by a creature. Client uses this for damage flash animation.
     * [type] = damage type: 1=combat, 0=non-combat (client expects this field).
     * [markDamage] = always 1 (flag, not amount).
     */
    fun damageDealt(sourceInstanceId: Int, targetId: Int, amount: Int, type: Int = 1, markDamage: Int = 1): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DamageDealt_af5a)
            .setAffectorId(sourceInstanceId)
            .addAffectedIds(targetId)
            .addDetails(uint32Detail(DetailKeys.DAMAGE, amount))
            .addDetails(uint32Detail(DetailKeys.TYPE, type))
            .addDetails(uint32Detail(DetailKeys.MARK_DAMAGE, markDamage))
            .build()

    /** Player life total changed. Client uses this for life counter animation. */
    fun modifiedLife(playerSeatId: Int, lifeDelta: Int, affectorId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ModifiedLife)
            .apply { if (affectorId != 0) setAffectorId(affectorId) }
            .addAffectedIds(playerSeatId)
            .addDetails(int32Detail(DetailKeys.LIFE, lifeDelta))
            .build()

    /** Card's power changed. State parser — P/T values from gameObject fields, not annotation.
     *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID. */
    fun modifiedPower(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ModifiedPower)
            .addAffectedIds(instanceId)
            .build()

    /** Card's toughness changed. State parser — P/T values from gameObject fields, not annotation.
     *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID. */
    fun modifiedToughness(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ModifiedToughness)
            .addAffectedIds(instanceId)
            .build()

    /**
     * Player lost the game. client annotation type 2 (LossOfGame_af5a).
     * [affectedPlayerSeatId] = seat of the losing player.
     * [reason] = [AnnotationLossReason] (LifeTotal, Concede).
     */
    fun lossOfGame(affectedPlayerSeatId: Int, reason: AnnotationLossReason): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.LossOfGame_af5a)
            .addAffectedIds(affectedPlayerSeatId)
            .addDetails(int32Detail(DetailKeys.REASON, reason.wireValue))
            .build()

    /** Generic combat result marker. Client dispatches synthetic GameRulesEvent based on type. */
    fun syntheticEvent(attackerIid: Int, targetSeatId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.SyntheticEvent)
            .setAffectorId(attackerIid)
            .addAffectedIds(targetSeatId)
            .addDetails(uint32Detail(DetailKeys.TYPE, 1))
            .build()

    /** Persistent annotation: card entered a zone this turn. Client uses for summoning sickness, ETB display. */
    fun enteredZoneThisTurn(zoneId: Int, vararg instanceIds: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.EnteredZoneThisTurn)
            .setAffectorId(zoneId)
            .apply { instanceIds.forEach { addAffectedIds(it) } }
            .build()

    // -- Group A+ annotation builders (attachments) --

    /** Transient: Aura/Equipment attached to target. client type 70 (AttachmentCreated).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Wire shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachmentCreated(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AttachmentCreated)
            .setAffectorId(auraIid)
            .addAffectedIds(targetIid)
            .build()

    /** Persistent: Ongoing attachment relationship. client type 20 (Attachment).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Wire shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachment(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Attachment)
            .setAffectorId(auraIid)
            .addAffectedIds(targetIid)
            .build()

    /** Transient: Aura/Equipment detached from target. client type 12 (RemoveAttachment).
     *  [auraIid] = the aura/equipment instanceId that was removed. */
    fun removeAttachment(auraIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RemoveAttachment)
            .addAffectedIds(auraIid)
            .build()

    // -- Group B+ annotation builders (reveals) --

    /** Card revealed to all players. client type 59 (RevealedCardCreated).
     *  [instanceId] = the revealed card's instanceId. */
    fun revealedCardCreated(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RevealedCardCreated)
            .addAffectedIds(instanceId)
            .build()

    /** Card un-revealed (no longer visible). client type 60 (RevealedCardDeleted).
     *  [instanceId] = the card's instanceId being removed from revealed zone. */
    fun revealedCardDeleted(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RevealedCardDeleted)
            .addAffectedIds(instanceId)
            .build()

    // -- Group B annotation builders --

    /** Token was created. client type 35 (TokenCreated).
     *  [instanceId] = the new token's instanceId in the game state. */
    fun tokenCreated(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TokenCreated)
            .addAffectedIds(instanceId)
            .build()

    /** Token was destroyed (left battlefield). client type 41 (TokenDeleted).
     *  [instanceId] = the token's instanceId. */
    fun tokenDeleted(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TokenDeleted)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .build()

    /** Counter added to a permanent. client type 16 (CounterAdded). */
    fun counterAdded(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterAdded)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Counter removed from a permanent. client type 17 (CounterRemoved). */
    fun counterRemoved(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterRemoved)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Library shuffled. client type 56 (Shuffle). */
    fun shuffle(seatId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Shuffle)
            .addAffectedIds(seatId)
            .build()

    /** Scry action. client annotation type 65 (Scry_af5a). */
    fun scry(seatId: Int, topCount: Int, bottomCount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Scry_af5a)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.TOP_COUNT, topCount))
            .addDetails(int32Detail(DetailKeys.BOTTOM_COUNT, bottomCount))
            .build()

    // -- Tier 1 state annotations --

    /** Counter state: authoritative counter count on a permanent. client type 14 (Counter_803b).
     *  Three-parser pattern: type 14 (this, state) + 16 (CounterAdded, event) + 17 (CounterRemoved, event).
     *  [counterType] = numeric counter type (1 = +1/+1). */
    fun counter(instanceId: Int, counterType: Int, count: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Counter_803b)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.COUNT, count))
            .addDetails(int32Detail(DetailKeys.COUNTER_TYPE, counterType))
            .build()

    /**
     * Persistent annotation for ability word condition tracking.
     *
     * Wire shape:
     * - types: [AbilityWordActive]
     * - affectorId: creature instanceId (or seat=1 for Descended)
     * - affectedIds: [creature instanceId]
     * - details: AbilityWordName (always), value/threshold/AbilityGrpId (quantitative only)
     */
    fun abilityWordActive(
        instanceId: Int,
        abilityWordName: String,
        value: Int? = null,
        threshold: Int? = null,
        abilityGrpId: Int? = null,
        affectorId: Int = instanceId,
        affectedIds: List<Int> = listOf(instanceId),
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.AbilityWordActive)
        .setAffectorId(affectorId)
        .apply { affectedIds.forEach { addAffectedIds(it) } }
        .addDetails(typedStringDetail(DetailKeys.ABILITY_WORD_NAME, abilityWordName))
        .apply {
            if (value != null) addDetails(int32Detail(DetailKeys.VALUE, value))
            if (threshold != null) addDetails(int32Detail(DetailKeys.THRESHOLD, threshold))
            if (abilityGrpId != null) addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId))
        }
        .build()

    /**
     * Keyword grant via layered effect — multi-creature form.
     * Types: [AddAbility_af5a, LayeredEffect]. One pAnn covers all affected creatures.
     *
     * Wire shape: flat affectedIds list, one UniqueAbilityId per creature, shared grpId.
     */
    fun addAbilityMulti(
        affectedIds: List<Int>,
        grpId: Int,
        effectId: Int,
        uniqueAbilityIds: List<Int>,
        originalAbilityObjectZcid: Int,
        affectorId: Int,
    ): AnnotationInfo {
        val builder = AnnotationInfo.newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId)
            .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
            .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
        affectedIds.forEach { builder.addAffectedIds(it) }
        uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
        return builder.build()
    }

    /**
     * Multi-keyword grant for auras (e.g. Flying + First Strike from Angelic Destiny).
     * Packs multiple grpIds/UniqueAbilityIds into one [AddAbility+LayeredEffect] pAnn.
     */
    fun addAbilityPacked(
        affectedId: Int,
        grpIds: List<Int>,
        effectId: Int,
        uniqueAbilityIds: List<Int>,
        originalAbilityObjectZcids: List<Int>,
        affectorId: Int,
    ): AnnotationInfo {
        require(grpIds.size == uniqueAbilityIds.size) { "grpIds and uniqueAbilityIds must match" }
        val builder = AnnotationInfo.newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId)
            .addAffectedIds(affectedId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
        grpIds.forEach { builder.addDetails(uint32Detail(DetailKeys.GRPID, it)) }
        uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
        originalAbilityObjectZcids.forEach {
            builder.addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, it))
        }
        return builder.build()
    }

    /**
     * Persistent annotation marking a card as eligible for an alternate cast
     * (adventure from exile, etc.).
     *
     * grpId=196 appears universal for adventure Qualification — likely a
     * fixed ability ID, not per-card.
     */
    fun qualification(
        instanceId: Int,
        qualificationType: Int = 47,
        qualificationSubtype: Int = 0,
        grpId: Int = AnnotationConstants.ADVENTURE_QUALIFICATION_GRP_ID,
        sourceParent: Int = 0,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.Qualification)
        .addAffectedIds(instanceId)
        .addDetails(uint32Detail(DetailKeys.SOURCE_PARENT, sourceParent))
        .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
        .addDetails(uint32Detail(DetailKeys.QUALIFICATION_SUBTYPE, qualificationSubtype))
        .addDetails(uint32Detail(DetailKeys.QUALIFICATION_TYPE, qualificationType))
        .build()

    // -- Tier 1 state annotations (abilities, effects, designations) --

    /** Granted ability state. client type 9 (AddAbility_af5a). */
    fun addAbility(
        instanceId: Int,
        grpId: Int,
        effectId: Int,
        uniqueAbilityId: Int,
        originalAbilityObjectZcid: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.GRPID, grpId))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
            .build()

    /** Ability removed by effect. client type 23 (RemoveAbility). */
    fun removeAbility(instanceId: Int, effectId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RemoveAbility)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
            .build()

    /** Per-ability use tracking. client type 82 (AbilityExhausted). */
    fun abilityExhausted(
        instanceId: Int,
        abilityGrpId: Int,
        usesRemaining: Int,
        uniqueAbilityId: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AbilityExhausted)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId))
            .addDetails(int32Detail(DetailKeys.USES_REMAINING, usesRemaining))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .build()

    /** Designation gained (Monarch, City's Blessing, Initiative). client type 46 (GainDesignation).
     *  Event parser — emits DesignationCreatedEvent. */
    fun gainDesignation(seatId: Int, designationType: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.GainDesignation)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Designation state (persistent). client type 45 (Designation).
     *  Stub — always-present key only. Full version needs PromptMessage, CostIncrease,
     *  grpid, ActivePlayerSpellCount, value, ColorIdentity (context needed). */
    fun designation(seatId: Int, designationType: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Designation)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Layered effect creation event (buff/debuff started). client type 18 (LayeredEffectCreated).
     *  Transient — fires once when the effect begins. No detail keys on this annotation;
     *  all metadata lives on the companion LayeredEffect persistent annotation.
     *  [affectorId] = ability instance on stack that created the effect (optional — ~35% omitted). */
    fun layeredEffectCreated(effectId: Int, affectorId: Int? = null): AnnotationInfo {
        val builder = AnnotationInfo.newBuilder()
            .addType(AnnotationType.LayeredEffectCreated)
            .addAffectedIds(effectId)
        if (affectorId != null) {
            builder.affectorId = affectorId
        }
        return builder.build()
    }

    /** Layered effect state (continuous effects). client type 51 (LayeredEffect).
     *  Persistent — present in every GSM while the effect is active.
     *
     *  Client expects multi-type arrays: `[ModifiedToughness, ModifiedPower, LayeredEffect]`
     *  for P/T buffs — the co-types are part of the contract (drive client animation dispatch).
     *  [affectorId] = the affected creature (for P/T buffs), not the ability instance.
     *  [sourceAbilityGrpId] = ability grpId that created the effect (drives specific VFX, e.g. Prowess).
     *
     *  No `LayeredEffectType` for P/T buffs — client only expects it for CopyObject. */
    fun layeredEffect(
        instanceId: Int,
        effectId: Int,
        powerDelta: Int = 0,
        toughnessDelta: Int = 0,
        affectorId: Int = 0,
        sourceAbilityGrpId: Int? = null,
    ): AnnotationInfo {
        val builder = AnnotationInfo.newBuilder()
        // Multi-type: co-type with ModifiedPower/ModifiedToughness for P/T buffs
        if (toughnessDelta != 0) builder.addType(AnnotationType.ModifiedToughness)
        if (powerDelta != 0) builder.addType(AnnotationType.ModifiedPower)
        builder.addType(AnnotationType.LayeredEffect)
        builder.addAffectedIds(instanceId)
        if (affectorId != 0) builder.affectorId = affectorId
        builder.addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
        if (sourceAbilityGrpId != null) {
            builder.addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId))
        }
        return builder.build()
    }

    // -- Tier 2 detail-carrying annotations --

    /** Land color production for card frame rendering. client type 110 (ColorProduction).
     *  [colors] = client ManaColor ordinals (W=1, U=2, B=3, R=4, G=5). */
    fun colorProduction(instanceId: Int, colors: List<Int>): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ColorProduction)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(int32ListDetail(DetailKeys.COLORS, colors))
            .build()

    /** Which object triggered an ability + source zone. client type 32 (TriggeringObject). */
    fun triggeringObject(instanceId: Int, sourceZone: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TriggeringObject)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZone))
            .build()

    /** Target specification for spells/abilities. client type 26 (TargetSpec). */
    fun targetSpec(
        instanceId: Int,
        affectorId: Int,
        abilityGrpId: Int,
        index: Int,
        promptId: Int,
        promptParameters: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TargetSpec)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId))
            .addDetails(int32Detail(DetailKeys.INDEX, index))
            .addDetails(int32Detail(DetailKeys.PROMPT_ID, promptId))
            .addDetails(int32Detail(DetailKeys.PROMPT_PARAMETERS, promptParameters))
            .build()

    /** P/T modification event (buff animation). client type 71 (PowerToughnessModCreated).
     *  [affectorId] = source of the P/T change (ability instance or card). */
    fun powerToughnessModCreated(instanceId: Int, power: Int, toughness: Int, affectorId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PowerToughnessModCreated)
            .addAffectedIds(instanceId)
            .apply { if (affectorId != 0) setAffectorId(affectorId) }
            .addDetails(int32Detail(DetailKeys.POWER, power))
            .addDetails(int32Detail(DetailKeys.TOUGHNESS, toughness))
            .build()

    /** Card displayed under another card (exile-under-permanent, imprint, adventure exile).
     *  client type 38 (DisplayCardUnderCard). Persistent while source permanent remains.
     *  Wire shape: affectorId=sourcePermanentIid, affectedIds=[exiledCardIid]. */
    fun displayCardUnderCard(affectorId: Int, instanceId: Int, disable: Int = 0, temporaryZoneTransfer: Int = 1): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DisplayCardUnderCard)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.DISABLE, disable))
            .addDetails(int32Detail(DetailKeys.TEMPORARY_ZONE_TRANSFER, temporaryZoneTransfer))
            .build()

    /** Predicted direct damage preview text. client type 66 (PredictedDirectDamage). */
    fun predictedDirectDamage(instanceId: Int, value: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PredictedDirectDamage)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.VALUE, value))
            .build()

    // -- Controller change annotations --

    /** Transient: controller changed event. client type 15 (ControllerChanged).
     *  Wire shape: affectorId = spell/ability instance, affectedIds = [stolen permanent].
     *  No details field. */
    fun controllerChanged(affectorId: Int, instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ControllerChanged)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .build()

    /** Persistent: controller change continuous effect. Types: [ControllerChanged, LayeredEffect].
     *  Details: effect_id. Persists while steal is active; removed on expiry. */
    fun controllerChangedEffect(affectorId: Int, instanceId: Int, effectId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ControllerChanged)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
            .build()

    // -- Tier 2 detail-less annotations --

    /** Layered effect ended. [affectorId] = source of the destruction (e.g. aura iid for
     *  SBA_UnattachedAura; 0/omitted for EOT expiry). client type 19. */
    fun layeredEffectDestroyed(effectId: Int, affectorId: Int = 0): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.LayeredEffectDestroyed)
            .apply { if (affectorId != 0) setAffectorId(affectorId) }
            .addAffectedIds(effectId)
            .build()

    /** Player is selecting targets for a spell/ability. client type 92. */
    fun playerSelectingTargets(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PlayerSelectingTargets)
            .addAffectedIds(instanceId)
            .build()

    /** Player submitted target selections. client type 93. */
    fun playerSubmittedTargets(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PlayerSubmittedTargets)
            .addAffectedIds(instanceId)
            .build()

    /**
     * Persistent: vehicle was crewed this turn. client type 94 (CrewedThisTurn).
     * Wire shape: affectorId = vehicle instanceId, affectedIds = crew source instanceIds.
     * Emitted when crew resolves; persists until end of turn.
     */
    fun crewedThisTurn(vehicleInstanceId: Int, crewSourceInstanceIds: List<Int>): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CrewedThisTurn)
            .setAffectorId(vehicleInstanceId)
            .apply { crewSourceInstanceIds.forEach { addAffectedIds(it) } }
            .build()

    /**
     * Persistent: vehicle became a creature via crew (type change). Types: [ModifiedType, LayeredEffect].
     * Wire shape: affectedIds = [vehicleInstanceId], effect_id, sourceAbilityGRPID (crew ability grpId).
     * Emitted when crew resolves and vehicle gains Creature type; removed on expiry.
     */
    fun modifiedTypeLayeredEffect(
        instanceId: Int,
        effectId: Int,
        affectorId: Int = 0,
        sourceAbilityGrpId: Int? = null,
    ): AnnotationInfo {
        val builder = AnnotationInfo.newBuilder()
            .addType(AnnotationType.ModifiedType)
            .addType(AnnotationType.LayeredEffect)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
        if (affectorId != 0) builder.setAffectorId(affectorId)
        if (sourceAbilityGrpId != null) {
            builder.addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId))
        }
        return builder.build()
    }

    /** Creature was dealt damage this turn. Persistent state badge. client type 90. */
    fun damagedThisTurn(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DamagedThisTurn)
            .addAffectedIds(instanceId)
            .build()

    /**
     * Copy token with EOT sacrifice. Persistent annotation. client type 80.
     * Drives "sacrifice at end of turn" visual indicator on the client.
     * [abilityGrpId] = 192424 (universal EOT-sacrifice marker per protocol).
     */
    fun temporaryPermanent(
        tokenInstanceId: Int,
        abilityGrpId: Int = AnnotationConstants.EOT_SACRIFICE_GRP_ID,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.TemporaryPermanent)
        .setAffectorId(tokenInstanceId)
        .addAffectedIds(tokenInstanceId)
        .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId))
        .build()

    /** Card in hidden zone revealed to opponent. Persistent badge. client type 75. */
    fun instanceRevealedToOpponent(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.InstanceRevealedToOpponent)
            .addAffectedIds(instanceId)
            .build()

    /** Keyword qualification badge on a permanent. Persistent. client type 42.
     *  [grpId] = keyword grpId (e.g. 142 for Menace).
     *  [qualificationType] = client qualification subtype (e.g. 40 for combat keyword).
     *  [sourceParent] = instanceId of the permanent granting the keyword (usually self). */
    fun qualification(
        affectorId: Int,
        instanceId: Int,
        grpId: Int,
        qualificationType: Int,
        qualificationSubtype: Int = 0,
        sourceParent: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Qualification)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
            .addDetails(uint32Detail(DetailKeys.QUALIFICATION_TYPE, qualificationType))
            .addDetails(uint32Detail(DetailKeys.QUALIFICATION_SUBTYPE, qualificationSubtype))
            .addDetails(uint32Detail(DetailKeys.SOURCE_PARENT, sourceParent))
            .build()

    private fun typedStringDetail(key: String, value: String): KeyValuePairInfo =
        KeyValuePairInfo.newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.String)
            .addValueString(value)
            .build()

    private fun uint32Detail(key: String, value: Int): KeyValuePairInfo =
        KeyValuePairInfo.newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.Uint32)
            .addValueUint32(value)
            .build()

    private fun int32Detail(key: String, value: Int): KeyValuePairInfo =
        KeyValuePairInfo.newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.Int32)
            .addValueInt32(value)
            .build()

    private fun int32ListDetail(key: String, values: List<Int>): KeyValuePairInfo =
        KeyValuePairInfo.newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.Int32)
            .apply { values.forEach { addValueInt32(it) } }
            .build()
}
