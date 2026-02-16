package forge.nexus.game

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/** Builds Arena-format annotations for GameStateMessage. */
object AnnotationBuilder {

    fun zoneTransfer(
        instanceId: Int,
        srcZoneId: Int,
        destZoneId: Int,
        category: String,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ZoneTransfer_af5a)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("zone_src", srcZoneId))
        .addDetails(int32Detail("zone_dest", destZoneId))
        .addDetails(typedStringDetail("category", category))
        .build()

    /** Spell/ability begins resolving. Client uses this to start resolution animation. */
    fun resolutionStart(instanceId: Int, grpId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ResolutionStart)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail("grpid", grpId))
            .build()

    /** Phase/step changed. Client uses this to animate the phase tracker. */
    fun phaseOrStepModified(): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.PhaseOrStepModified)
            .build()

    /** Card's instanceId changed (e.g. zone move creates new object). */
    fun objectIdChanged(origId: Int, newId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.ObjectIdChanged)
            .addAffectedIds(origId)
            // TODO: re-enable after client crash bisect
            // .addDetails(int32Detail("orig_id", origId))
            // .addDetails(int32Detail("new_id", newId))
            .build()

    /**
     * Ties a game state change back to a player interaction.
     * [seatId] = acting player's seat (affectorId).
     * [actionType] = Arena ActionType ordinal (1=Cast, 3=Play, 4=ActivateMana).
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

    /** Permanent tapped or untapped (e.g. tapping land for mana). */
    fun tappedUntappedPermanent(instanceId: Int): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.TappedUntappedPermanent)
            .addAffectedIds(instanceId)
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
