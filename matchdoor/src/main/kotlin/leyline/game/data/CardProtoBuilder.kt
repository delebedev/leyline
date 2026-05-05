package leyline.game.data

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Builds static [GameObjectInfo] proto projections from [CardRepository] data.
 *
 * Covers the immutable card identity (types, colors, abilities, base P/T).
 * Dynamic game state — counters, damage, tapped, attached, combat — is layered
 * on by [leyline.game.mapping.ObjectMapper]. The split keeps card-DB concerns
 * out of the per-tick diff pipeline.
 */
class CardProtoBuilder(
    private val cards: CardRepository,
) {
    /**
     * Basic land mana ability grpIds — implicit in the client, not stored in DB.
     * SubType enum values: Plains=54, Island=43, Swamp=69, Mountain=49, Forest=29.
     */
    private val basicLandAbilities =
        mapOf(
            54 to 1001, // Plains → {T}: Add {W}
            43 to 1002, // Island → {T}: Add {U}
            69 to 1003, // Swamp → {T}: Add {B}
            49 to 1004, // Mountain → {T}: Add {R}
            29 to 1005, // Forest → {T}: Add {G}
        )

    /** Returns the implicit mana ability grpId for a basic land, or null. */
    private fun basicLandAbility(subtypes: List<Int>): Int? = subtypes.firstNotNullOfOrNull { basicLandAbilities[it] }

    /**
     * Door-state ability grpIds prefixed on every Room enchantment's
     * `uniqueAbilities` list (left door, right door). Constant across all rooms;
     * surfaces the locked/unlocked indicators the client renders. Without these
     * the client renders a Room as a plain enchantment and skips the side-by-side
     * door display.
     */
    private val roomDoorAbilityGrpIds = listOf(347, 348)

    /** SubType ordinal for `Room` (matchdoor proto Messages.SubType.Room = 438). */
    private val roomSubtype = SubType.Room.number

    private fun isRoomCard(subtypes: List<Int>): Boolean = subtypes.contains(roomSubtype)

    /** Build a [GameObjectInfo] from DB data, no template — for the buildFromSnapshot path. */
    fun buildObjectInfo(
        grpId: Int,
        extrinsicKeywordGrpIds: List<Int> = emptyList(),
    ): GameObjectInfo.Builder {
        val builder =
            GameObjectInfo
                .newBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
        val card = cards.findByGrpId(grpId) ?: return builder
        builder.setName(card.titleId)
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }
        if (card.power.isNotEmpty()) builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        if (card.toughness.isNotEmpty()) builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        var abilitySeqId = 50
        if (isRoomCard(card.subtypes)) {
            for (doorGrpId in roomDoorAbilityGrpIds) {
                builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(doorGrpId))
            }
        }
        val abilities =
            card.abilityIds.ifEmpty {
                basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
            }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId))
        }
        for (kwGrpId in extrinsicKeywordGrpIds) {
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(kwGrpId))
        }
        return builder
    }

    /** Build a [GameObjectInfo] from DB data, preserving template structure fields. */
    fun buildObjectInfo(
        grpId: Int,
        template: GameObjectInfo,
        extrinsicKeywordGrpIds: List<Int> = emptyList(),
    ): GameObjectInfo {
        val card =
            cards.findByGrpId(grpId) ?: return template
                .toBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
                .build()

        val builder =
            template
                .toBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
                .setName(card.titleId)

        builder.clearCardTypes()
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }

        builder.clearSubtypes()
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }

        builder.clearSuperTypes()
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }

        builder.clearColor()
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }

        if (card.power.isNotEmpty()) {
            builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        } else {
            builder.clearPower()
        }
        if (card.toughness.isNotEmpty()) {
            builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        } else {
            builder.clearToughness()
        }

        builder.clearUniqueAbilities()
        var abilitySeqId = template.uniqueAbilitiesList.firstOrNull()?.id ?: 50
        if (isRoomCard(card.subtypes)) {
            for (doorGrpId in roomDoorAbilityGrpIds) {
                builder.addUniqueAbilities(
                    UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(doorGrpId),
                )
            }
        }
        val abilities =
            card.abilityIds.ifEmpty {
                basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
            }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(
                UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId),
            )
        }
        for (kwGrpId in extrinsicKeywordGrpIds) {
            builder.addUniqueAbilities(
                UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(kwGrpId),
            )
        }

        return builder.build()
    }
}
