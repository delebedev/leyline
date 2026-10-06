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
    companion object {
        // Avoid the large generated switch in browser JVM card projection.
        private val subtypesByNumber = SubType.values().filter { it != SubType.UNRECOGNIZED }.associateBy { it.number }
    }

    /**
     * Door-state ability grpIds prefixed on every Room enchantment's
     * `uniqueAbilities` list (left door, right door). Constant across all rooms;
     * surfaces the locked/unlocked indicators the client renders. Without these
     * the client renders a Room as a plain enchantment and skips the side-by-side
     * door display.
     */
    private val roomDoorAbilityGrpIds = listOf(347, 348)

    /** SubType ordinal for `Room` (engine proto Messages.SubType.Room = 438). */
    private val roomSubtype = SubType.Room.number

    private fun isRoomCard(subtypes: List<Int>): Boolean = subtypes.contains(roomSubtype)

    /**
     * Universal face-down overlay grpId — the "card back" stencil the
     * client renders in place of the real card art for any face-down
     * permanent.
     */
    private val faceDownOverlayGrpId = 3

    /** Face-down stat — every face-down creature is a 2/2 regardless of printed P/T. */
    private val faceDownPowerAndToughness = 2

    /**
     * Build a [GameObjectInfo] for a supported face-down permanent. The
     * projection drops printed identity (name,
     * subtypes, color, the per-card abilities) and substitutes the
     * universal face-down stencil, 2/2 P/T, and `Creature` card type.
     * Intrinsic Ward {2} is included when the caller's mechanic provides it.
     * Independently granted keyword abilities remain visible.
     *
     * The [grpId] of the underlying card is still set on the proto so the
     * per-seat filter can preserve it for the controller and substitute
     * the stencil identity for the opponent.
     */
    fun buildFaceDownObjectInfo(
        grpId: Int,
        hasIntrinsicWard: Boolean,
        extrinsicKeywordGrpIds: List<Int> = emptyList(),
    ): GameObjectInfo.Builder =
        GameObjectInfo
            .newBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(faceDownOverlayGrpId)
            .setIsFacedown(true)
            .addCardTypes(CardType.Creature)
            .setPower(Int32Value.newBuilder().setValue(faceDownPowerAndToughness))
            .setToughness(Int32Value.newBuilder().setValue(faceDownPowerAndToughness))
            .apply {
                val intrinsicAbilities = if (hasIntrinsicWard) listOf(KeywordAbilityIds.WARD_TWO) else emptyList()
                (intrinsicAbilities + extrinsicKeywordGrpIds).forEachIndexed { index, abilityGrpId ->
                    addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(50 + index).setGrpId(abilityGrpId))
                }
            }

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
        card.types.forEach { value ->
            projectCardValue(grpId, "types", value) {
                builder.addCardTypes(CardType.forNumber(value) ?: return@projectCardValue)
            }
        }
        card.subtypes.forEach { value ->
            projectCardValue(grpId, "subtypes", value) {
                builder.addSubtypes(subtypesByNumber[value] ?: return@projectCardValue)
            }
        }
        card.supertypes.forEach { value ->
            projectCardValue(grpId, "supertypes", value) {
                builder.addSuperTypes(SuperType.forNumber(value) ?: return@projectCardValue)
            }
        }
        card.colors.forEach { value ->
            projectCardValue(grpId, "colors", value) {
                builder.addColor(CardColor.forNumber(value) ?: return@projectCardValue)
            }
        }
        if (card.power.isNotEmpty()) builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        if (card.toughness.isNotEmpty()) builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        var abilitySeqId = 50
        staticAbilityGrpIds(grpId).forEach { abilityGrpId ->
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId))
        }
        for (kwGrpId in extrinsicKeywordGrpIds) {
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(kwGrpId))
        }
        return builder
    }

    fun staticAbilityGrpIds(grpId: Int): List<Int> {
        val card = cards.findByGrpId(grpId) ?: return emptyList()
        return buildList {
            if (isRoomCard(card.subtypes)) addAll(roomDoorAbilityGrpIds)
            val abilities =
                card.abilityIds.ifEmpty {
                    BasicLandAbilities.byProtoSubtypeOrdinals(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
                }
            addAll(
                abilities.map { it.first },
            )
        }
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
        card.types.forEach { value ->
            projectCardValue(grpId, "types", value) {
                builder.addCardTypes(CardType.forNumber(value) ?: return@projectCardValue)
            }
        }

        builder.clearSubtypes()
        card.subtypes.forEach { value ->
            projectCardValue(grpId, "subtypes", value) {
                builder.addSubtypes(subtypesByNumber[value] ?: return@projectCardValue)
            }
        }

        builder.clearSuperTypes()
        card.supertypes.forEach { value ->
            projectCardValue(grpId, "supertypes", value) {
                builder.addSuperTypes(SuperType.forNumber(value) ?: return@projectCardValue)
            }
        }

        builder.clearColor()
        card.colors.forEach { value ->
            projectCardValue(grpId, "colors", value) {
                builder.addColor(CardColor.forNumber(value) ?: return@projectCardValue)
            }
        }

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
                BasicLandAbilities.byProtoSubtypeOrdinals(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
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

/** Numeric context is added only when a card value fails projection; the cause remains intact. */
internal inline fun <T> projectCardValue(
    grpId: Int,
    field: String,
    value: Int,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: RuntimeException) {
        throw IllegalStateException("Card projection failed: grpId=$grpId field=$field value=$value", failure)
    }
