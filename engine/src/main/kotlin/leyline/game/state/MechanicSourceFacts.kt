package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ZoneIds
import kotlin.ConsistentCopyVisibility

/**
 * Immutable source attribution observed for the mechanic events in one frame.
 *
 * The shell records only identities referenced by the closed event frame.
 * Projection uses these values when an event does not already carry the
 * source detail it needs; no live card lookup is deferred into reduction.
 */
@ConsistentCopyVisibility
data class MechanicSourceFacts private constructor(
    val sourceZoneByForgeCardId: Map<ForgeCardId, Int>,
    val tokenCreatorByTokenForgeCardId: Map<ForgeCardId, TokenCreator>,
) {
    companion object {
        operator fun invoke(
            sourceZoneByForgeCardId: Map<ForgeCardId, Int> = emptyMap(),
            tokenCreatorByTokenForgeCardId: Map<ForgeCardId, TokenCreator> = emptyMap(),
        ): MechanicSourceFacts =
            MechanicSourceFacts(
                unmodifiable(sourceZoneByForgeCardId),
                unmodifiable(tokenCreatorByTokenForgeCardId),
            )

        private fun <K, V> unmodifiable(values: Map<K, V>): Map<K, V> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
    }

    /** Source zone at the frame cut, with the established battlefield default. */
    fun sourceZone(forgeCardId: ForgeCardId): Int = sourceZoneByForgeCardId[forgeCardId] ?: ZoneIds.BATTLEFIELD

    /** Source zone when this exact card was observed for the closed frame. */
    fun recordedSourceZone(forgeCardId: ForgeCardId): Int? = sourceZoneByForgeCardId[forgeCardId]

    /** Fallback creator of a token whose event omitted source identity. */
    data class TokenCreator(
        val sourceForgeCardId: ForgeCardId,
        val sourceAbilityForgeId: Int,
    ) {
        init {
            require(sourceAbilityForgeId != 0) { "Token creator ability id must be non-zero" }
        }
    }
}
