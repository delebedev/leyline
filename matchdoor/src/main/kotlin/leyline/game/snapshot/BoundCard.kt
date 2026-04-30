package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardData

/**
 * Per-frame bound view of one card — pairs the live [CardSnapshot] with the
 * static [CardData] resolved at snapshot time.
 *
 * The cornerstone of the architectural-pressure epic (S2.A in `leyline-y3pf`):
 * consumers read [data] instead of reaching back into `bridge.cardRepository`.
 * As phases land, more pre-resolved fields move onto BoundCard (alt-cost
 * bindings, keyword presence, modal options, parent linkage, designations) and
 * `CardSnapshot` shrinks until Phase 7 retires it entirely.
 *
 * In Phase 0, BoundCard is a thin wrapper: [snapshot] holds the live state,
 * [data] holds the static metadata (null when no DB row exists, e.g. `EFFECT`
 * pieces or unknown grpIds). No consumers read from BoundCard yet — Phase 1
 * starts the migration with `keywords` + `altCosts`.
 */
data class BoundCard(
    val forgeCardId: ForgeCardId,
    val snapshot: CardSnapshot,
    val data: CardData?,
)
