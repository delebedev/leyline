package leyline.game.snapshot

import forge.card.CardStateName
import forge.card.GamePieceType
import forge.game.card.Card
import leyline.game.data.CardRepository

/**
 * Pure recognition + lookup for the Prepared card-state designation.
 *
 * Forge models prepared-spell copies as `GamePieceType.TOKEN` with an active
 * `CardStateName.PreparedSpell` face — but the protocol treats them as ordinary
 * Cards with a real card-DB grpId. This module concentrates the "Forge says X,
 * protocol means Y" reasoning so consumers (`SnapshotCapture`, `ObjectMapper`)
 * each call one function instead of repeating the state-name guards.
 *
 * Functions are pure on their inputs — no `Game` parameter, no walks, no shared
 * state. Cross-card linkages (Source ↔ Copy) are computed by the snapshot
 * pipeline once per pass and exposed via [PreparedRole]; this module only
 * answers "what is this single card?".
 */
object PreparedSpell {
    /**
     * True when [card] is a prepared-spell copy — `GamePieceType.TOKEN` carrying
     * the `PreparedSpell` face as its current state. Stable across the Forge
     * `Card.id` reallocation that happens when a copy moves Exile → Stack on cast.
     */
    fun isCopy(card: Card): Boolean =
        card.gamePieceType == GamePieceType.TOKEN &&
            card.hasState(CardStateName.PreparedSpell) &&
            card.currentState?.stateName == CardStateName.PreparedSpell

    /**
     * Resolve a prepared-spell copy's grpId by name lookup against [cards].
     * Returns null when [card] is not a prepared copy or the name doesn't appear
     * in the card repository.
     */
    fun resolveCopyGrpId(
        card: Card,
        cards: CardRepository,
    ): Int? {
        if (!isCopy(card)) return null
        return cards.findGrpIdByName(card.name)
            ?: cards.findGrpIdByNameAnyFace(card.name)
    }
}
