package leyline.game.snapshot

import forge.game.card.Card
import forge.game.zone.ZoneType

/**
 * Pure recognition for the Foretell mechanic — a card sitting face-down in
 * Exile with the foretold flag set, waiting to be cast on a later turn for
 * its foretell cost.
 *
 * ## Why this exists
 *
 * Foretell is a card-state mechanic (Card.foretold flag) similar in shape to
 * Plot, but differs in three protocol-significant ways:
 *
 * 1. **No `Designation` pAnn.** Foretold cards don't carry a persistent
 *    Designation (DesignationType=18 is reserved for Plotted; foretold has no
 *    designation type allocated). Face-down exile is the wire signal.
 * 2. **Face-down exile.** `visibility=Private` on the exile object, a keyed
 *    `FaceDown` row while it remains foretold, and the transient `FaceDown` +
 *    `SuppressedPowerAndToughness` pair on the foretell action GSM.
 * 3. **Cast-leg uses type=13 CastingTimeOption rail** (like Flashback), not
 *    the universal-149 no-mana rail (like Plot). Foretell still pays a mana
 *    cost — just at the foretell discount.
 *
 * ## What's pure here
 *
 * Single function takes one Forge `Card`, no `Game` parameter. The
 * `isInZone(Exile)` filter mirrors `Plotted.kt`'s zone filter: Forge's
 * `Card.isForetold()` can return true on the cast SA's host card mid-cast
 * even when the underlying Card has moved Exile→Stack. We only want to flag
 * the live exile object.
 */
object Foretell {
    /**
     * True when [card] is currently sitting in Exile with the foretold state.
     *
     * The `isInZone(Exile)` filter is load-bearing — Forge's `isForetold()`
     * returns true on retired stack-form card states mid-resolve, and we only
     * want to anchor the FaceDown/Suppressed pair on the live exile object.
     */
    fun isForetold(card: Card): Boolean = card.isForetold && card.isInZone(ZoneType.Exile)
}
