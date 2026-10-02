package leyline.game.snapshot

import forge.game.card.Card
import leyline.bridge.types.ForgeCardId
import leyline.game.codes.SlotKind
import leyline.game.data.CardData
import leyline.game.state.GameBridge

/** Stable emblem lineage and the abilities carried by its Command-zone object. */
data class EmblemSnapshot(
    val sourceGrpId: Int,
    val parentInstanceId: Int,
    val abilityGrpIds: List<Int>,
) {
    companion object {
        const val GRP_ID = 2

        /** Hidden source rows define emblem abilities, independently of its universal object identity. */
        fun abilityData(
            card: Card,
            bridge: GameBridge,
        ): CardData? {
            if (!card.isEmblem) return null
            val sourceGrpId =
                bridge.emblemLineage(ForgeCardId(card.id))?.sourceGrpId
                    ?: card.effectSource?.let(bridge::resolveGrpId) ?: return null
            val source = bridge.cardRepository.findByGrpId(sourceGrpId) ?: return null
            val rows = source.hiddenAbilityIds
            if (rows.isEmpty()) return null
            val categories = rows.mapNotNull { bridge.cardRepository.findAbilityInfo(it.first)?.category }
            return source.copy(
                abilityIds = rows,
                abilityKinds =
                    if (categories.size ==
                        rows.size
                    ) {
                        categories.map { if (it == 1) SlotKind.Activated else SlotKind.Intrinsic }
                    } else {
                        emptyList()
                    },
                abilityCategories = categories.takeIf { it.size == rows.size }.orEmpty(),
                hiddenAbilityIds = emptyList(),
            )
        }

        fun capture(
            card: Card,
            bridge: GameBridge,
        ): EmblemSnapshot? {
            if (!card.isEmblem) return null
            bridge.emblemLineage(ForgeCardId(card.id))?.let { return it }
            val source = card.effectSource ?: return null
            val data = abilityData(card, bridge) ?: return null
            val registry = bridge.abilityRegistryFor(card, data) ?: return null
            val ids =
                card.triggers.mapNotNull { registry.forTrigger(it.definitionId) } +
                    card.staticAbilities.mapNotNull { registry.forStaticAbility(it.definitionId) } +
                    card.spellAbilities.mapNotNull { registry.forSpellAbility(it) }
            return EmblemSnapshot(data.grpId, bridge.instanceId(source), ids.distinct())
        }
    }
}
