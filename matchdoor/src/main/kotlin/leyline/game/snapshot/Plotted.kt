package leyline.game.snapshot

import forge.game.card.Card
import forge.game.zone.ZoneType

/**
 * Pure recognition for the Plotted card-state designation.
 *
 * ## Why this exists
 *
 * Plotted is a card-state designation (DesignationType=18) for cards with the
 * plot keyword. Activating the plot ability exiles the card face-up with the
 * plotted state; on a later turn the controller may cast it from exile at
 * sorcery speed without paying its mana cost.
 *
 * Structurally simpler than Prepared: the plotted card itself is in exile —
 * there's no copy, no parentId, no Source/Copy pair. So the Role is single-
 * variant (`None | Plotted`), the recognizer is one predicate (`isPlotted`),
 * and there's no [PreparedLinkage]-style cross-card class.
 *
 * ## What's pure here
 *
 * The single function takes one Forge `Card`, no `Game` parameter. The
 * `isOnExile` filter mirrors `PreparedSpell.kt`'s `isOnBattlefield` filter on
 * the Source side: Forge keeps `Card.isPlotted==true` on retired card states
 * after the live exile copy inherits the flag. Without the zone filter we'd
 * anchor a Designation pAnn on a stale iid.
 */
object Plotted {
    /**
     * True when [card] is currently sitting in Exile with the plotted state.
     *
     * The `isInZone(Exile)` filter is load-bearing — Forge keeps `isPlotted`
     * on retired stack/limbo card states even after the card has settled in
     * exile, and we only want to anchor the Designation pAnn on the live
     * exile object.
     */
    fun isPlotted(card: Card): Boolean = card.isPlotted && card.isInZone(ZoneType.Exile)
}
