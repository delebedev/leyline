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
     */
    fun categoryFromEvents(forgeCardId: Int, events: List<NexusGameEvent>): TransferCategory? {
        // Walk events in reverse — most recent event for this card wins.
        // Specific events (LandPlayed, SpellCast, SpellResolved) take priority
        // over generic ZoneChanged.
        var generic: NexusGameEvent.ZoneChanged? = null
        for (ev in events) {
            when (ev) {
                is NexusGameEvent.LandPlayed -> if (ev.forgeCardId == forgeCardId) return TransferCategory.PlayLand
                is NexusGameEvent.SpellCast -> if (ev.forgeCardId == forgeCardId) return TransferCategory.CastSpell
                is NexusGameEvent.SpellResolved -> if (ev.forgeCardId == forgeCardId) return TransferCategory.Resolve
                is NexusGameEvent.ZoneChanged -> if (ev.forgeCardId == forgeCardId) generic = ev
                else -> {} // CardTapped, Damage, Life, etc. don't affect zone-transfer category
            }
        }
        // Fall back to generic zone change if we saw one but no specific event
        if (generic != null) {
            return zoneChangedCategory(generic)
        }
        return null
    }

    /** Map a generic ZoneChanged event to an annotation category. */
    private fun zoneChangedCategory(ev: NexusGameEvent.ZoneChanged): TransferCategory = when {
        ev.from == forge.game.zone.ZoneType.Hand -> when (ev.to) {
            forge.game.zone.ZoneType.Battlefield -> TransferCategory.PlayLand
            forge.game.zone.ZoneType.Stack -> TransferCategory.CastSpell
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == forge.game.zone.ZoneType.Stack && ev.to == forge.game.zone.ZoneType.Battlefield ->
            TransferCategory.Resolve
        ev.from == forge.game.zone.ZoneType.Battlefield -> when (ev.to) {
            forge.game.zone.ZoneType.Graveyard -> TransferCategory.Destroy
            forge.game.zone.ZoneType.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
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
