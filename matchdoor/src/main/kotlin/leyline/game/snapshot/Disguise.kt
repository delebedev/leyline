package leyline.game.snapshot

import forge.game.card.Card
import forge.game.zone.ZoneType

/**
 * Pure recognition for the Disguise mechanic — a card sitting face-down on the
 * battlefield as a 2/2 with ward {2}, awaiting a Special_TurnFaceUp activation
 * for its printed disguise cost.
 *
 * ## Why this exists
 *
 * Disguise is a face-down card-state mechanic. Both the projected client
 * envelope (`overlayGrpId=3`, no name/subtypes/color, single ability `141939`)
 * and the paired persistent annotations (`FaceDown { REASON=6, abilityGrpId=
 * 307 }`, transient `TurnPermanent` on flip) all key off "is this card
 * currently face-down on the battlefield?".
 *
 * Forge's `Card.isFaceDown()` returns true for any reason a card might be
 * face-down (Morph, Manifest, Cloak, Disguise, hidden agenda). The mechanic
 * is identified by a co-keyword on the underlying card. For the v1 cut we
 * scope to Disguise specifically — Morph and Manifest have different REASON
 * codes and different cast rails (Morph is also from-hand but has no ward;
 * Manifest is a separate non-keyword effect).
 *
 * ## What's pure here
 *
 * Single function takes one Forge `Card`, no `Game` parameter. The
 * `isInZone(Battlefield)` filter mirrors `Plotted.kt` / `Foretell.kt`:
 * `Card.isFaceDown()` can return true on retired stack-form card states
 * mid-resolve, and we only want to anchor projection / FaceDown pAnn on
 * the live battlefield permanent.
 *
 * The `hasKeyword("Disguise")` check is on the original (face-up) state —
 * Forge's face-down state strips the keywords list, so a check against
 * `card.hasKeyword(Keyword.DISGUISE)` mid-face-down would always return
 * false. We reach into the original state for the keyword presence test.
 */
object Disguise {
    /**
     * True when [card] is currently face-down on the battlefield AND its
     * underlying (face-up) keywords include `Disguise`.
     *
     * Excludes face-down-in-exile (foretell handles that — different shape)
     * and retired stack/limbo states (filtered by zone check).
     */
    fun isFaceDownDisguise(card: Card): Boolean {
        if (!card.isFaceDown) return false
        if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Stack)) return false
        // Reach the original (face-up) state to test the keyword — the
        // current face-down state has its keywords list zeroed out by
        // Forge's face-down rules.
        val original =
            card.getOriginalState(forge.card.CardStateName.Original)
                ?: return false
        return original.intrinsicKeywords.any { it.original.startsWith("Disguise") }
    }
}
