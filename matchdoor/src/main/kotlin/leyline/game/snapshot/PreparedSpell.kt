package leyline.game.snapshot

import forge.card.CardStateName
import forge.card.GamePieceType
import forge.game.Game
import forge.game.card.Card
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
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

/**
 * Source ↔ Copy linkage built once per snapshot pass. Backed by two reverse
 * indexes over the same set of pairs — single source of truth for who's
 * prepared and which Forge `Card` carries the spell-face copy. Lookups by
 * either end resolve in O(1) and never disagree.
 */
class PreparedLinkage private constructor(
    private val sourceToCopy: Map<ForgeCardId, ForgeCardId>,
    private val copyToSource: Map<ForgeCardId, ForgeCardId>,
) {
    /** Forge id of the prepared-spell copy linked to [source], or null if none. */
    fun copyOf(source: ForgeCardId): ForgeCardId? = sourceToCopy[source]

    /** Forge id of the live battlefield source whose copy is [copy], or null. */
    fun sourceOf(copy: ForgeCardId): ForgeCardId? = copyToSource[copy]

    companion object {
        /**
         * Walk the live battlefield in [game], pair every prepared source with
         * its `prepared.firstRemembered` copy, and build the reverse indexes.
         * Sources with a null `firstRemembered` are silently skipped — the
         * SnapshotCapture path logs them via the `isPrepared && firstRemembered==null`
         * branch.
         */
        fun from(game: Game): PreparedLinkage {
            val sourceToCopy = mutableMapOf<ForgeCardId, ForgeCardId>()
            val copyToSource = mutableMapOf<ForgeCardId, ForgeCardId>()
            for (perm in game.getCardsIn(ZoneType.Battlefield)) {
                if (!perm.isPrepared) continue
                val copy = perm.prepared?.firstRemembered as? Card ?: continue
                val sourceId = ForgeCardId(perm.id)
                val copyId = ForgeCardId(copy.id)
                sourceToCopy[sourceId] = copyId
                copyToSource[copyId] = sourceId
            }
            return PreparedLinkage(sourceToCopy, copyToSource)
        }
    }
}
