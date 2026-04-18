package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

/**
 * Immutable snapshot of one card's observable state. Fields grow as mappers migrate:
 * Task 1 (skeleton): identity-only.
 * Task 4 (ZoneMapper): adds `zone: ZoneId`.
 * Task 6 (ObjectMapper): adds power/toughness/tapped/keywords/counters/attachedTo/combat-state.
 * Task 8 (ActionMapper): adds flags ActionMapper reads (abilities, cost materials).
 */
data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,
)
