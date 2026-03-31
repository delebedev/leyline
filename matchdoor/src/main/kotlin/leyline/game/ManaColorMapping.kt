package leyline.game

import forge.card.mana.ManaCostShard
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Single source of truth for Forge mana representation → Arena [ManaColor] mapping.
 *
 * Two entry points:
 * - [fromShard] — [ManaCostShard] enum (used by card cost derivation)
 * - [fromProduced] — string like "W", "G", "Any" (used by mana ability / action mapping)
 *
 * Also provides [deriveManaCost] for converting a Forge [forge.card.mana.ManaCost]
 * into the `List<Pair<ManaColor, Int>>` format used by [CardData].
 */
object ManaColorMapping {

    /** ManaCostShard → proto ManaColor. Only simple shards mapped; hybrids skipped. */
    val SHARD_MAP: Map<ManaCostShard, ManaColor> = mapOf(
        ManaCostShard.WHITE to ManaColor.White_afc9,
        ManaCostShard.BLUE to ManaColor.Blue_afc9,
        ManaCostShard.BLACK to ManaColor.Black_afc9,
        ManaCostShard.RED to ManaColor.Red_afc9,
        ManaCostShard.GREEN to ManaColor.Green_afc9,
        ManaCostShard.COLORLESS to ManaColor.Colorless_afc9,
        ManaCostShard.X to ManaColor.X,
    )

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    fun fromProduced(produced: String): ManaColor? = when (produced.uppercase().trim()) {
        "W" -> ManaColor.White_afc9
        "U" -> ManaColor.Blue_afc9
        "B" -> ManaColor.Black_afc9
        "R" -> ManaColor.Red_afc9
        "G" -> ManaColor.Green_afc9
        "C" -> ManaColor.Colorless_afc9
        "ANY" -> ManaColor.Generic
        else -> null
    }

    /** Map a [ManaCostShard] to proto ManaColor, or null if unmapped (hybrid, etc.). */
    fun fromShard(shard: ManaCostShard): ManaColor? = SHARD_MAP[shard]

    /**
     * Derive `(ManaColor, count)` pairs from a Forge [ManaCost].
     * Shared by [PuzzleCardRegistrar] and test `CardDataDeriver`.
     */
    fun deriveManaCost(cost: forge.card.mana.ManaCost?): List<Pair<ManaColor, Int>> {
        if (cost == null || cost.isNoCost) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        val generic = cost.genericCost
        if (generic > 0) counts[ManaColor.Generic] = generic
        for (shard in cost) {
            val color = SHARD_MAP[shard] ?: continue
            counts.merge(color, 1, Int::plus)
        }
        return counts.toList()
    }
}
