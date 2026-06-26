package leyline.bridge.coord

import forge.card.ColorSet
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard

internal object ConvokeShardAssigner {
    private val coloredShards =
        listOf(
            ManaCostShard.WHITE,
            ManaCostShard.BLUE,
            ManaCostShard.BLACK,
            ManaCostShard.RED,
            ManaCostShard.GREEN,
        )

    fun <T> assign(
        sources: List<T>,
        costCounts: Map<ManaCostShard, Int>,
        colorOf: (T) -> ColorSet,
    ): List<Pair<T, ManaCostShard>> {
        val remaining = costCounts.filterValues { it > 0 }
        if (sources.isEmpty() || remaining.isEmpty()) return emptyList()

        val memo = mutableMapOf<SearchKey, List<Pair<T, ManaCostShard>>>()

        fun search(
            index: Int,
            counts: Map<ManaCostShard, Int>,
        ): List<Pair<T, ManaCostShard>> {
            if (index >= sources.size || counts.isEmpty()) return emptyList()
            val key = SearchKey(index, counts)
            memo[key]?.let { return it }

            val source = sources[index]
            var best = emptyList<Pair<T, ManaCostShard>>()
            for (shard in payableShards(colorOf(source), counts)) {
                val candidate = listOf(source to shard) + search(index + 1, counts.decrement(shard))
                if (candidate.size > best.size) best = candidate
            }

            val skipped = search(index + 1, counts)
            if (skipped.size > best.size) best = skipped
            memo[key] = best
            return best
        }

        return search(0, remaining)
    }

    fun costCounts(manaCost: ManaCost): Map<ManaCostShard, Int> =
        buildMap {
            if (manaCost.genericCost > 0) put(ManaCostShard.GENERIC, manaCost.genericCost)
            for (shard in coloredShards) {
                val count = manaCost.getShardCount(shard)
                if (count > 0) put(shard, count)
            }
        }

    private fun payableShards(
        color: ColorSet,
        counts: Map<ManaCostShard, Int>,
    ): List<ManaCostShard> =
        buildList {
            if (color.hasWhite() && counts.has(ManaCostShard.WHITE)) add(ManaCostShard.WHITE)
            if (color.hasBlue() && counts.has(ManaCostShard.BLUE)) add(ManaCostShard.BLUE)
            if (color.hasBlack() && counts.has(ManaCostShard.BLACK)) add(ManaCostShard.BLACK)
            if (color.hasRed() && counts.has(ManaCostShard.RED)) add(ManaCostShard.RED)
            if (color.hasGreen() && counts.has(ManaCostShard.GREEN)) add(ManaCostShard.GREEN)
            if (counts.has(ManaCostShard.GENERIC)) add(ManaCostShard.GENERIC)
        }

    private fun Map<ManaCostShard, Int>.has(shard: ManaCostShard): Boolean = getOrDefault(shard, 0) > 0

    private fun Map<ManaCostShard, Int>.decrement(shard: ManaCostShard): Map<ManaCostShard, Int> {
        val next = toMutableMap()
        val value = (next[shard] ?: 0) - 1
        if (value <= 0) next.remove(shard) else next[shard] = value
        return next
    }

    private data class SearchKey(
        val index: Int,
        val white: Int,
        val blue: Int,
        val black: Int,
        val red: Int,
        val green: Int,
        val generic: Int,
    ) {
        constructor(index: Int, counts: Map<ManaCostShard, Int>) : this(
            index = index,
            white = counts.getOrDefault(ManaCostShard.WHITE, 0),
            blue = counts.getOrDefault(ManaCostShard.BLUE, 0),
            black = counts.getOrDefault(ManaCostShard.BLACK, 0),
            red = counts.getOrDefault(ManaCostShard.RED, 0),
            green = counts.getOrDefault(ManaCostShard.GREEN, 0),
            generic = counts.getOrDefault(ManaCostShard.GENERIC, 0),
        )
    }
}
