package forge.nexus.game

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/** Builds client-format annotations for GameStateMessage. */
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
    fun categoryFromEvents(forgeCardId: Int, events: List<NexusGameEvent>): TransferCategory? {
        var generic: NexusGameEvent.ZoneChanged? = null
        var sacrificed = false
        var zoneCategory: TransferCategory? = null

        for (ev in events) {
            when (ev) {
                // Highest priority — mechanic-specific events (immediate return)
                is NexusGameEvent.LandPlayed -> if (ev.forgeCardId == forgeCardId) return TransferCategory.PlayLand
                is NexusGameEvent.SpellCast -> if (ev.forgeCardId == forgeCardId) return TransferCategory.CastSpell
                is NexusGameEvent.SpellResolved -> if (ev.forgeCardId == forgeCardId) {
                    // Fizzled spells (countered) go Stack→GY — not a successful resolve
                    if (ev.hasFizzled) {
                        zoneCategory = TransferCategory.Countered
                    } else {
                        return TransferCategory.Resolve
                    }
                }
                // Sacrifice flag — overrides Destroy when both fire for same card
                is NexusGameEvent.CardSacrificed -> if (ev.forgeCardId == forgeCardId) sacrificed = true
                // Zone-specific events (emitted by enriched ZoneChanged handler)
                is NexusGameEvent.CardDestroyed -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Destroy
                is NexusGameEvent.CardBounced -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Bounce
                is NexusGameEvent.CardExiled -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Exile
                is NexusGameEvent.CardDiscarded -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Discard
                is NexusGameEvent.CardMilled -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Mill
                is NexusGameEvent.SpellCountered -> if (ev.forgeCardId == forgeCardId) zoneCategory = TransferCategory.Countered
                // Generic zone change — fallback, infer category from zone pair
                is NexusGameEvent.ZoneChanged -> if (ev.forgeCardId == forgeCardId) generic = ev
                // Other events (tapped, damage, life, counters, etc.) don't affect transfer category
                else -> {}
            }
        }

        // Zone-specific events take priority over generic ZoneChanged
        if (zoneCategory != null) {
            // CardSacrificed overrides CardDestroyed (BF→GY) when both fire
            if (sacrificed && zoneCategory == TransferCategory.Destroy) return TransferCategory.Sacrifice
            return zoneCategory
        }

        // Fallback: generic ZoneChanged → zone-pair heuristic
        if (generic != null) {
            if (sacrificed) return TransferCategory.Sacrifice
            return zoneChangedCategory(generic)
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
    private fun zoneChangedCategory(ev: NexusGameEvent.ZoneChanged): TransferCategory = when {
        ev.from == forge.game.zone.ZoneType.Hand -> when (ev.to) {
            forge.game.zone.ZoneType.Battlefield -> TransferCategory.PlayLand
            forge.game.zone.ZoneType.Stack -> TransferCategory.CastSpell
            forge.game.zone.ZoneType.Graveyard -> TransferCategory.Discard
            forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == forge.game.zone.ZoneType.Stack -> when (ev.to) {
            forge.game.zone.ZoneType.Battlefield -> TransferCategory.Resolve
            forge.game.zone.ZoneType.Graveyard -> TransferCategory.Countered
            forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == forge.game.zone.ZoneType.Battlefield -> when (ev.to) {
            forge.game.zone.ZoneType.Graveyard -> TransferCategory.Destroy
            forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
            forge.game.zone.ZoneType.Hand -> TransferCategory.Bounce
            forge.game.zone.ZoneType.Library -> TransferCategory.Bounce
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == forge.game.zone.ZoneType.Library -> when (ev.to) {
            forge.game.zone.ZoneType.Hand -> TransferCategory.Draw
            forge.game.zone.ZoneType.Graveyard -> TransferCategory.Mill
            forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.to == forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
        else -> TransferCategory.ZoneTransfer
    }

    fun zoneTransfer(
        instanceId: Int,
        srcZoneId: Int,
        destZoneId: Int,
        category: String,
        actingSeatId: Int = 0,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ZoneTransfer_af5a)
        .apply { if (actingSeatId != 0) setAffectorId(actingSeatId) }
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("zone_src", srcZoneId))
        .addDetails(int32Detail("zone_dest", destZoneId))
        .addDetails(typedStringDetail("category", category))
        .build()

    /** Spell/ability begins resolving. Client uses this to start resolution animation. */
    fun resolutionStart(instanceId: Int, grpId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ResolutionStart)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail("grpid", grpId))
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
            .addDetails(int32Detail("phase", phase))
            .addDetails(int32Detail("step", step))
            .build()

    /** Card's instanceId changed (e.g. zone move creates new object). */
    fun objectIdChanged(origId: Int, newId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ObjectIdChanged)
            .addAffectedIds(origId)
            .addDetails(int32Detail("orig_id", origId))
            .addDetails(int32Detail("new_id", newId))
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
            .addDetails(int32Detail("actionType", actionType))
            .addDetails(int32Detail("abilityGrpId", abilityGrpId))
            .build()

    /** Mana was spent to pay for a spell/ability. */
    fun manaPaid(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ManaPaid)
            .addAffectedIds(instanceId)
            .build()

    /**
     * Permanent tapped or untapped (e.g. tapping land for mana).
     * [permanentId] = the permanent being tapped (affectedIds).
     * [abilityId] = the ability instance that caused the tap (affectorId).
     *   Real server uses a transient mana ability id; we approximate with the spell id.
     */
    fun tappedUntappedPermanent(permanentId: Int, abilityId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TappedUntappedPermanent)
            .setAffectorId(abilityId)
            .addAffectedIds(permanentId)
            .addDetails(uint32Detail("tapped", 1))
            .build()

    /** Ability instance created on the stack. */
    fun abilityInstanceCreated(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AbilityInstanceCreated)
            .addAffectedIds(instanceId)
            .build()

    /** Ability instance deleted (e.g. hand's play ability consumed after casting). */
    fun abilityInstanceDeleted(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AbilityInstanceDeleted)
            .addAffectedIds(instanceId)
            .build()

    /** Spell/ability done resolving. Client uses this to finalize stack→battlefield move. */
    fun resolutionComplete(instanceId: Int, grpId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ResolutionComplete)
            .setAffectorId(instanceId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail("grpid", grpId))
            .build()

    /** Combat damage dealt by a creature. Client uses this for damage flash animation. */
    fun damageDealt(sourceInstanceId: Int, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.DamageDealt_af5a)
            .addAffectedIds(sourceInstanceId)
            .addDetails(uint32Detail("damage", amount))
            .build()

    /** Player life total changed. Client uses this for life counter animation. */
    fun modifiedLife(playerSeatId: Int, lifeDelta: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ModifiedLife)
            .addAffectedIds(playerSeatId)
            .addDetails(int32Detail("delta", lifeDelta))
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
            .addDetails(int32Detail("reason", reason))
            .build()

    /** Generic combat result marker. Client uses this to finalize combat animations. */
    fun syntheticEvent(): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.SyntheticEvent)
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
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent. */
    fun attachmentCreated(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.AttachmentCreated)
            .addAffectedIds(auraIid)
            .addAffectedIds(targetIid)
            .build()

    /** Persistent: Ongoing attachment relationship. Arena type 20 (Attachment).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent. */
    fun attachment(auraIid: Int, targetIid: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Attachment)
            .addAffectedIds(auraIid)
            .addAffectedIds(targetIid)
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

    /** Counter added to a permanent. Arena type 16 (CounterAdded). */
    fun counterAdded(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterAdded)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail("counter_type", counterType))
            .addDetails(int32Detail("transaction_amount", amount))
            .build()

    /** Counter removed from a permanent. Arena type 17 (CounterRemoved). */
    fun counterRemoved(instanceId: Int, counterType: String, amount: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.CounterRemoved)
            .addAffectedIds(instanceId)
            .addDetails(typedStringDetail("counter_type", counterType))
            .addDetails(int32Detail("transaction_amount", amount))
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
            .addDetails(int32Detail("topCount", topCount))
            .addDetails(int32Detail("bottomCount", bottomCount))
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
}
