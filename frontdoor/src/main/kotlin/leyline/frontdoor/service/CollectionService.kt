package leyline.frontdoor.service

import leyline.frontdoor.domain.PlayerId
import org.slf4j.LoggerFactory

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
 * client's cached version. We always return the full set with a stable positive
 * cache version so the client accepts the replacement collection.
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

    /** Serialize collection to the wire format expected by CmdType 551. */
    fun toJson(collection: Map<Int, Int>): String {
        val cards = collection.entries.joinToString(",") { (grpId, count) -> "\"$grpId\":$count" }
        return """{"cacheVersion":2,"cards":{$cards}}"""
    }
}
