package leyline.game

import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Builds client-format [AnnotationInfo] protos for [GameStateMessage] bundles.
 *
 * Arena annotations are the **semantic layer** on top of raw game state diffs.
 * The client has two independent parser families that consume them:
 * - **State parsers** (~58 classes): mutate card/game state fields directly
 *   (P/T, counters, abilities, designations, attachments)
 * - **Event parsers** (~46 classes): produce [GameRulesEvent] objects that drive
 *   the animation/sound pipeline (zone transfers, damage flash, life counter)
 *
 * Some annotation types fire **both** parsers (e.g. ResolutionStart, Shuffle,
 * DieRoll). Each builder method here maps to one [AnnotationType] enum value
 * and matches the real server's detail key names, value types, and shape.
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
 * [categoryFromEvents] bridges Forge's event model to Arena's annotation
 * categories — resolves which [TransferCategory] label to stamp on each
 * zone transfer annotation based on captured [GameEvent]s.
 *
 * Authoritative client parser reference: from Arena client decompilation (annotation registry)
 *
 * @see AnnotationPipeline for the pipeline that calls these builders
 * @see GameEvent for the Forge→protocol event translation layer
 * @see TransferCategory for the category label enum
 */
object AnnotationBuilder {

    /**
     * Resolve the annotation category for a zone transfer using captured events.
     *
     * Looks up the forge card ID in the event list and returns the category
     * based on the **most specific** event (LandPlayed > ZoneChanged, etc.).
     * Returns null if no matching event was found — caller should fall back
     * to [StateMapper.inferCategory].
     *
     * Priority: specific mechanic events > CardSacrificed override > zone-pair inference.
     */
    fun categoryFromEvents(forgeCardId: ForgeCardId, events: List<GameEvent>): TransferCategory? {
        var generic: GameEvent.ZoneChanged? = null
        var sacrificed = false
        var zoneCategory: TransferCategory? = null

        for (ev in events) {
            when (ev) {
                // Highest priority — mechanic-specific events (immediate return)
                is GameEvent.LandPlayed -> if (ev.cardId == forgeCardId) return TransferCategory.PlayLand
                is GameEvent.SpellCast -> if (ev.cardId == forgeCardId) return TransferCategory.CastSpell
                is GameEvent.SpellResolved -> if (ev.cardId == forgeCardId) {
                    // Fizzled spells (countered) go Stack→GY — not a successful resolve
                    if (ev.hasFizzled) {
                        zoneCategory = TransferCategory.Countered
                    } else {
                        return TransferCategory.Resolve
                    }
                }
                // Legend rule SBA — highest zone-specific priority (immediate return)
                is GameEvent.LegendRuleDeath -> if (ev.cardId == forgeCardId) return TransferCategory.SbaLegendRule
                // Sacrifice flag — overrides Destroy when both fire for same card
                is GameEvent.CardSacrificed -> if (ev.cardId == forgeCardId) sacrificed = true
                // Zone-specific events (emitted by enriched ZoneChanged handler)
                is GameEvent.CardDestroyed -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Destroy
                is GameEvent.CardBounced -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Bounce
                is GameEvent.CardExiled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Exile
                is GameEvent.CardDiscarded -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Discard
                is GameEvent.CardMilled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Mill
                is GameEvent.CardSurveiled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Surveil
                is GameEvent.CardSearchedToHand -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Put
                is GameEvent.SpellCountered -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Countered
                // Generic zone change — fallback, infer category from zone pair
                is GameEvent.ZoneChanged -> if (ev.cardId == forgeCardId) generic = ev
                // Other events (tapped, damage, life, counters, etc.) don't affect transfer category
                else -> {}
            }
        }

        // Zone-specific events take priority over generic ZoneChanged
        if (zoneCategory != null) {
            // CardSacrificed overrides CardDestroyed (BF→GY) when both fire
            return if (sacrificed && zoneCategory == TransferCategory.Destroy) {
                TransferCategory.Sacrifice
            } else {
                zoneCategory
            }
        }

        // Fallback: generic ZoneChanged → zone-pair heuristic
        return when {
            generic != null && sacrificed -> TransferCategory.Sacrifice
            generic != null -> zoneChangedCategory(generic)
            else -> null
        }
    }

    /**
     * Extract the source Forge card ID for the ability that caused a zone transfer.
     *
     * Used to resolve the affectorId on annotations. Currently only CardSurveiled
     * carries source info; extend for other mechanics as needed.
     *
     * @return Forge card ID of the causing ability's host card, or null if unknown.
     */
    fun affectorSourceFromEvents(forgeCardId: ForgeCardId, events: List<GameEvent>): ForgeCardId? {
        for (ev in events) {
            when {
                ev is GameEvent.CardMilled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardSurveiled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardDestroyed && ev.cardId == forgeCardId -> return ev.sourceCardId
            }
        }
        return null
    }

    /**
     * Map a generic ZoneChanged event to an annotation category using zone-pair heuristics.
     *
     * This covers Group A categories that lack dedicated Forge events:
     * Destroy (BF→GY), Bounce (BF→Hand), Draw (Lib→Hand), Discard (Hand→GY),
     * Mill (Lib→GY), Countered (Stack→GY), and Exile (any→Exile).
     */
    private fun zoneChangedCategory(ev: GameEvent.ZoneChanged): TransferCategory = when {
        ev.from == Zone.Hand -> when (ev.to) {
            Zone.Battlefield -> TransferCategory.PlayLand
            Zone.Stack -> TransferCategory.CastSpell
            Zone.Graveyard -> TransferCategory.Discard
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Stack -> when (ev.to) {
            Zone.Battlefield -> TransferCategory.Resolve
            Zone.Graveyard -> TransferCategory.Countered
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Battlefield -> when (ev.to) {
            Zone.Graveyard -> TransferCategory.Destroy
            Zone.Exile -> TransferCategory.Exile
            Zone.Hand -> TransferCategory.Bounce
            Zone.Library -> TransferCategory.Bounce
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Library -> when (ev.to) {
            Zone.Hand -> TransferCategory.Draw
            Zone.Battlefield -> TransferCategory.Search
            Zone.Graveyard -> TransferCategory.Mill
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Graveyard -> when (ev.to) {
            Zone.Hand, Zone.Battlefield -> TransferCategory.Return
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Exile -> when (ev.to) {
            Zone.Hand, Zone.Battlefield -> TransferCategory.Return
            else -> TransferCategory.ZoneTransfer
        }
        ev.to == Zone.Exile -> TransferCategory.Exile
        else -> TransferCategory.ZoneTransfer
    }

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
     * [actionType] = client ActionType ordinal (1=Cast, 3=Play, 4=ActivateMana).
     * [abilityGrpId] = ability group ID (0 for land play).
     */
    fun userActionTaken(
        instanceId: Int,
        seatId: Int,
        actionType: Int = 0,
        abilityGrpId: Int = 0,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.UserActionTaken)
            .setAffectorId(seatId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.ACTION_TYPE, actionType))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId))
            .build()

    /**
     * Mana was spent to pay for a spell/ability.
     * [spellInstanceId] = the spell/ability instance that consumed the mana (affectedIds).
     * [landInstanceId] = the land (or mana source) that produced the mana (affectorId).
     * [manaId] = mana payment tracking ID (real server assigns sequentially).
     * [color] = mana color as int bitmask (e.g. 2 = blue), matching the Arena wire format.
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
     *   Real server uses a transient mana ability id; we approximate with the spell id.
     */
    fun tappedUntappedPermanent(permanentId: Int, abilityId: Int, tapped: Boolean = true): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TappedUntappedPermanent)
            .setAffectorId(abilityId)
            .addAffectedIds(permanentId)
            .addDetails(uint32Detail(DetailKeys.TAPPED, if (tapped) 1 else 0))
            .build()

    /**
     * Ability instance created on the stack.
     * [abilityInstanceId] = the ability/spell instance being created (affectedIds).
     * [affectorId] = the land or permanent that triggered this ability creation (e.g. tapping a land for mana).
     *   Pass 0 when not applicable (e.g. casting a spell from hand).
     * [sourceZoneId] = zone the ability/spell came from (e.g. Hand=31).
     * Real server always sends this; client may use it for animation origin.
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
     * [type] = damage type: 1=combat, 0=non-combat (real server always sends this).
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
     *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID
     *  (seen in session 09-33-05, grp:93848 with aura/counter effects). */
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
     * Player lost the game. Arena annotation type 2 (LossOfGame_af5a).
     * [affectedPlayerSeatId] = seat of the losing player.
     * [reason] = 0 (LifeTotal), 3 (Concede).
     */
    fun lossOfGame(affectedPlayerSeatId: Int, reason: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.LossOfGame_af5a)
            .addAffectedIds(affectedPlayerSeatId)
            .addDetails(int32Detail(DetailKeys.REASON, reason))
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

    /** Transient: Aura/Equipment attached to target. Arena type 70 (AttachmentCreated).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Wire shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachmentCreated(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AttachmentCreated)
            .setAffectorId(auraIid)
            .addAffectedIds(targetIid)
            .build()

    /** Persistent: Ongoing attachment relationship. Arena type 20 (Attachment).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Wire shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachment(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Attachment)
            .setAffectorId(auraIid)
            .addAffectedIds(targetIid)
            .build()

    /** Transient: Aura/Equipment detached from target. Arena type 12 (RemoveAttachment).
     *  [auraIid] = the aura/equipment instanceId that was removed. */
    fun removeAttachment(auraIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RemoveAttachment)
            .addAffectedIds(auraIid)
            .build()

    // -- Group B+ annotation builders (reveals) --

    /** Card revealed to all players. Arena type 59 (RevealedCardCreated).
     *  [instanceId] = the revealed card's instanceId. */
    fun revealedCardCreated(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RevealedCardCreated)
            .addAffectedIds(instanceId)
            .build()

    /** Card un-revealed (no longer visible). Arena type 60 (RevealedCardDeleted).
     *  [instanceId] = the card's instanceId being removed from revealed zone. */
    fun revealedCardDeleted(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RevealedCardDeleted)
            .addAffectedIds(instanceId)
            .build()

    // -- Group B annotation builders --

    /** Token was created. Arena type 35 (TokenCreated).
     *  [instanceId] = the new token's instanceId in the game state. */
    fun tokenCreated(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TokenCreated)
            .addAffectedIds(instanceId)
            .build()

    /** Token was destroyed (left battlefield). Arena type 41 (TokenDeleted).
     *  [instanceId] = the token's instanceId. */
    fun tokenDeleted(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TokenDeleted)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .build()

    /** Counter added to a permanent. Arena type 16 (CounterAdded). */
    fun counterAdded(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterAdded)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Counter removed from a permanent. Arena type 17 (CounterRemoved). */
    fun counterRemoved(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterRemoved)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Library shuffled. Arena type 56 (Shuffle). */
    fun shuffle(seatId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Shuffle)
            .addAffectedIds(seatId)
            .build()

    /** Scry action. Arena annotation type 65 (Scry_af5a). */
    fun scry(seatId: Int, topCount: Int, bottomCount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Scry_af5a)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.TOP_COUNT, topCount))
            .addDetails(int32Detail(DetailKeys.BOTTOM_COUNT, bottomCount))
            .build()

    // -- Tier 1 state annotations --

    /** Counter state: authoritative counter count on a permanent. Arena type 14 (Counter_803b).
     *  Three-parser pattern: type 14 (this, state) + 16 (CounterAdded, event) + 17 (CounterRemoved, event).
     *  [counterType] = numeric counter type (1 = +1/+1).
     *  Real card: grp:93848 with +1/+1 counter (session 09-33-05). */
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
     * Wire shape from recordings:
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
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.AbilityWordActive)
        .setAffectorId(affectorId)
        .addAffectedIds(instanceId)
        .addDetails(typedStringDetail(DetailKeys.ABILITY_WORD_NAME, abilityWordName))
        .apply {
            if (value != null) addDetails(int32Detail(DetailKeys.VALUE, value))
            if (threshold != null) addDetails(int32Detail(DetailKeys.THRESHOLD, threshold))
            if (abilityGrpId != null) addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId))
        }
        .build()

    /** Map Forge counter type name to proto CounterType numeric value.
     *  Forge's CounterEnumType.getName() returns display names ("+1/+1", "LOYAL")
     *  which differ from both the Java enum constant ("P1P1", "LOYALTY") and the
     *  proto enum name. We index both proto names and known Forge display names. */
    private val forgeNameToProtoNumber: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        for (ct in CounterType.entries) {
            if (ct == CounterType.UNRECOGNIZED) continue
            val base = ct.name.removeSuffix("_a40e").uppercase()
            map[base] = ct.number
        }
        // Forge display names that differ from proto enum names
        map["+1/+1"] = CounterType.P1P1.number
        map["-1/-1"] = CounterType.M1M1.number
        map["LOYAL"] = CounterType.Loyalty_a40e.number
        map
    }

    fun counterTypeId(forgeName: String): Int =
        forgeNameToProtoNumber[forgeName.uppercase()] ?: 0

    // -- Tier 1 state annotations (abilities, effects, designations) --

    /** Granted ability state. Arena type 9 (AddAbility_af5a).
     *  Real card: grp:92081 via effect 7005 (session 14-15-29). */
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

    /** Ability removed by effect. Arena type 23 (RemoveAbility).
     *  Real card: effect cleanup (session 2026-03-01, grp:92196). */
    fun removeAbility(instanceId: Int, effectId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.RemoveAbility)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
            .build()

    /** Per-ability use tracking. Arena type 82 (AbilityExhausted).
     *  Real card: grp:95039 activated ability exhausted (session 09-33-05). */
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

    /** Designation gained (Monarch, City's Blessing, Initiative). Arena type 46 (GainDesignation).
     *  Event parser — emits DesignationCreatedEvent.
     *  Real card: grp:92196, DesignationType=19 (session 2026-03-01). */
    fun gainDesignation(seatId: Int, designationType: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.GainDesignation)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Designation state (persistent). Arena type 45 (Designation).
     *  Stub — always-present key only. Full version needs PromptMessage, CostIncrease,
     *  grpid, ActivePlayerSpellCount, value, ColorIdentity (context needed).
     *  Real card: grp:92196 (session 2026-03-01). */
    fun designation(seatId: Int, designationType: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Designation)
            .addAffectedIds(seatId)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Layered effect creation event (buff/debuff started). Arena type 18 (LayeredEffectCreated).
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

    /** Layered effect state (continuous effects). Arena type 51 (LayeredEffect).
     *  Persistent — present in every GSM while the effect is active.
     *
     *  Real server uses multi-type arrays: `[ModifiedToughness, ModifiedPower, LayeredEffect]`
     *  for P/T buffs — the co-types are part of the contract (drive client animation dispatch).
     *  [affectorId] = the affected creature (for P/T buffs), not the ability instance.
     *  [sourceAbilityGrpId] = ability grpId that created the effect (drives specific VFX, e.g. Prowess).
     *
     *  No `LayeredEffectType` for P/T buffs — real server only uses it for CopyObject. */
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

    /** Land color production for card frame rendering. Arena type 110 (ColorProduction).
     *  [colors] = bitmask (1=W, 2=U, 4=B, 8=R, 16=G).
     *  Real card: grp:96188, colors=4 (session 09-33-05). */
    fun colorProduction(instanceId: Int, colors: List<Int>): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ColorProduction)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(int32ListDetail(DetailKeys.COLORS, colors))
            .build()

    /** Which object triggered an ability + source zone. Arena type 32 (TriggeringObject).
     *  Real card: grp:95039, zone=27 (session 09-33-05). */
    fun triggeringObject(instanceId: Int, sourceZone: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TriggeringObject)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZone))
            .build()

    /** Target specification for spells/abilities. Arena type 26 (TargetSpec).
     *  Real card: grp:75479, promptId=1330 (session 11-50-40). */
    fun targetSpec(
        instanceId: Int,
        abilityGrpId: Int,
        index: Int,
        promptId: Int,
        promptParameters: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TargetSpec)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId))
            .addDetails(int32Detail(DetailKeys.INDEX, index))
            .addDetails(int32Detail(DetailKeys.PROMPT_ID, promptId))
            .addDetails(int32Detail(DetailKeys.PROMPT_PARAMETERS, promptParameters))
            .build()

    /** P/T modification event (buff animation). Arena type 71 (PowerToughnessModCreated).
     *  Real card: grp:91865, +1/+1 (session 09-33-05).
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
     *  Arena type 38 (DisplayCardUnderCard). Persistent while source permanent remains.
     *  Wire shape: affectorId=sourcePermanentIid, affectedIds=[exiledCardIid]. */
    fun displayCardUnderCard(affectorId: Int, instanceId: Int, disable: Int = 0, temporaryZoneTransfer: Int = 1): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DisplayCardUnderCard)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.DISABLE, disable))
            .addDetails(int32Detail(DetailKeys.TEMPORARY_ZONE_TRANSFER, temporaryZoneTransfer))
            .build()

    /** Predicted direct damage preview text. Arena type 66 (PredictedDirectDamage).
     *  Real card: grp:58445, value=2 (session 2026-03-01). */
    fun predictedDirectDamage(instanceId: Int, value: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PredictedDirectDamage)
            .addAffectedIds(instanceId)
            .addDetails(int32Detail(DetailKeys.VALUE, value))
            .build()

    // -- Controller change annotations --

    /** Transient: controller changed event. Arena type 15 (ControllerChanged).
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

    /** Layered effect ended (continuous effect removed). Arena type 19. */
    fun layeredEffectDestroyed(effectId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.LayeredEffectDestroyed)
            .addAffectedIds(effectId)
            .build()

    /** Player is selecting targets for a spell/ability. Arena type 92. */
    fun playerSelectingTargets(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PlayerSelectingTargets)
            .addAffectedIds(instanceId)
            .build()

    /** Player submitted target selections. Arena type 93. */
    fun playerSubmittedTargets(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PlayerSubmittedTargets)
            .addAffectedIds(instanceId)
            .build()

    /** Creature was dealt damage this turn. Persistent state badge. Arena type 90. */
    fun damagedThisTurn(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DamagedThisTurn)
            .addAffectedIds(instanceId)
            .build()

    /** Card in hidden zone revealed to opponent. Persistent badge. Arena type 75. */
    fun instanceRevealedToOpponent(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.InstanceRevealedToOpponent)
            .addAffectedIds(instanceId)
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
