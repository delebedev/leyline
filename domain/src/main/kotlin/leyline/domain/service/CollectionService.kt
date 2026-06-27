package leyline.domain.service

import leyline.domain.PlayerId
import org.slf4j.LoggerFactory
import java.util.zip.CRC32

/**
 * Player card collection — what cards a player owns and how many copies.
 *
 * Current impl: full card DB × 250 (unrestricted collection for offline play).
 * Future: per-player restrictions from player DB, format-based filtering.
 *
 * ## Wire protocol (CmdType 551 — Card_GetAllCards)
 *
 * Client sends `{"CacheVersion": N}` where N is the last-seen version (-1 = fresh).
 * Server responds `{"cacheVersion": N, "cards": {"<grpId>": <count>, ...}}`.
 *
 * The protocol supports incremental updates — only changed cards after the
 * client's cached version. We always return the full set, with the cache version
 * derived from a content hash of the cards. A degraded boot that yields an empty
 * collection therefore reports a different version than a full one, so the client
 * re-fetches automatically once the underlying card data recovers — and an
 * unchanged collection keeps the same version, avoiding spurious re-fetches.
 */
class CollectionService(
    /** Provides all available card grpIds. Injected from CardRepository at wiring time. */
    private val allGrpIds: () -> List<Int>,
) {
    private val log = LoggerFactory.getLogger(CollectionService::class.java)

    /**
     * Returns the card collection for [playerId] as grpId → owned count.
     * Currently returns all non-token cards × 250 so seeded basic-land decks are redeemable.
     */
    fun getCollection(
        @Suppress("UnusedParameter") playerId: PlayerId?,
    ): Map<Int, Int> {
        val grpIds = allGrpIds()
        if (grpIds.isEmpty()) {
            log.warn("Card DB returned no grpIds — collection will be empty")
        } else {
            log.debug("Collection: {} cards (250x each)", grpIds.size)
        }
        return grpIds.associateWith { 250 }
    }

    /** Serialize collection to the JSON payload expected by CmdType 551. */
    fun toJson(collection: Map<Int, Int>): String {
        val cards =
            collection.entries
                .sortedBy { it.key }
                .joinToString(",") { (grpId, count) -> "\"$grpId\":$count" }
        return """{"cacheVersion":${cacheVersion(cards)},"cards":{$cards}}"""
    }

    /**
     * Content-derived cache version: CRC32 of the (sorted) cards payload, masked
     * to a non-negative Int. Same cards → same version; any change → new version.
     */
    private fun cacheVersion(cards: String): Int {
        val crc = CRC32()
        crc.update(cards.toByteArray())
        return (crc.value and 0x7FFF_FFFF).toInt()
    }
}
