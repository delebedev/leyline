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
 * ## Why this exists
 *
 * Forge models prepared-spell copies with `GamePieceType.TOKEN` and the
 * `CardStateName.PreparedSpell` alt face active. Two facts make consumers
 * miscategorize them:
 *
 * 1. **`gamePieceType.TOKEN` is overloaded.** It also covers engine-spawned
 *    tokens (Goblin tokens, Treasure tokens, copy tokens). The standard token
 *    branch in [GrpIdResolver] reads `tokenSpawningAbility.hostCard` to pin a
 *    grpId on the source spell — but a prepared copy has no such spawning
 *    ability (it was made by `CardCopyService`, not by a token-creator spell),
 *    so that branch falls through to `DevCheck.fail` with grpId=0.
 * 2. **The protocol's `GameObjectInfo` for a prepared copy is a normal Card,**
 *    with a real card-DB grpId on the spell face — not a Token. So the wire
 *    shape diverges from the Forge piece-type classification.
 *
 * This module concentrates the "Forge says X, protocol means Y" reasoning into
 * one place. Consumers ([SnapshotCapture], [GrpIdResolver]) each call one
 * function instead of repeating the state-name guards inline.
 *
 * ## Generalization
 *
 * The pattern — a sealed `Role` on `CardSnapshot`, a small recognizer module
 * for state-based detection, a snapshot-scoped linkage class for cross-card
 * pairs — applies to any card-state designation where Forge's piece-type and
 * id model don't match the protocol's object model. Saddled, Plotted, Day/Night,
 * Door states, and Commander all fit this shape. When implementing the next
 * one, mirror this file's structure rather than re-deriving the lessons.
 *
 * ## What's pure here
 *
 * Functions take a single Forge `Card` (and a `CardRepository` for name lookup)
 * — no `Game` parameter, no battlefield walks, no shared state. Cross-card
 * Source ↔ Copy linkages are not this module's concern; that lives in
 * [PreparedLinkage], built once per snapshot pass by [SnapshotCapture]. This
 * module only answers "what is this single card?".
 */
object PreparedSpell {
    /**
     * True when [card] is a prepared-spell copy — the alt face copy living in
     * exile (or briefly on the stack mid-cast).
     *
     * Detection is **state-based, not identity-based**. Forge reallocates the
     * copy's `Card.id` when it moves Exile → Stack on cast, so any check that
     * depends on a stable id (or on referential equality with a remembered
     * `Card` instance) breaks for the stack-form copy. The triple guard below
     * — `gamePieceType.TOKEN` AND has-state PreparedSpell AND current-state IS
     * PreparedSpell — survives the reallocation because Forge transfers the
     * face state when it moves the card, not the identity.
     *
     * The third condition (current state) is what distinguishes a prepared
     * copy from the Source creature mid-resolve: `Card.copyCard(true, …)` clones
     * both faces, so a creature alt-face card has the PreparedSpell state too,
     * but its current state is the creature face. We only treat the alt-face-
     * active card as a Copy.
     */
    fun isCopy(card: Card): Boolean =
        card.gamePieceType == GamePieceType.TOKEN &&
            card.hasState(CardStateName.PreparedSpell) &&
            card.currentState?.stateName == CardStateName.PreparedSpell

    /**
     * Resolve a prepared-spell copy's grpId by name lookup against [cards].
     * Returns null when [card] is not a prepared copy or the name doesn't appear
     * in the card repository.
     *
     * The fallback chain (`findGrpIdByName ?: findGrpIdByNameAnyFace`) mirrors
     * the standard non-token branch in [GrpIdResolver]. The duplication is
     * intentional: this branch must run before the token-spawning-ability path
     * because prepared copies are `gamePieceType==TOKEN` but the standard chain
     * would crash on them.
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
 * Source ↔ Copy linkage built once per snapshot pass.
 *
 * ## Why this is a class, not just a Map
 *
 * Forge holds the linkage one direction only — `Card.preparedEffect` is
 * private, accessed via `Card.getPrepared()` returning the command-zone
 * effect, whose `firstRemembered` is the copy. So you can go Source → Copy
 * via `source.prepared.firstRemembered`, but there's no `Card.preparedSource`
 * field on the copy. Going Copy → Source from outside Forge requires walking
 * the battlefield.
 *
 * Without a shared linkage object, every per-card consumer that needs a Source
 * iid for a Copy (or vice versa) re-walks the battlefield. This class does
 * that walk **once per snapshot**, materializes both directions as O(1) maps,
 * and hands the same view to every consumer. Source and Copy roles in
 * [SnapshotCapture] read from the same instance, so a card never disagrees
 * with itself across the Source/Copy boundary even if Forge briefly mutates
 * `prepared` between two reads.
 *
 * ## Why not a single forward map
 *
 * Source → Copy alone forces the Copy-side reader to invert it — searching
 * the map's values for the matching source. That's O(n) per lookup, and
 * worse, easy to get wrong on multiple-prepared scenarios (two Honorbound
 * Pages on the battlefield: which Source owns this Copy?). The reverse
 * `copyToSource` map costs ~20 entries of memory and replaces the inversion
 * with a hash lookup.
 *
 * ## What it doesn't track
 *
 * Sources whose `prepared.firstRemembered` is null are silently skipped —
 * that's an in-flight Forge state ([SnapshotCapture] logs it separately so
 * we don't lose visibility). The map only contains pairs where both ends
 * are present and resolvable.
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
         *
         * Battlefield walk is O(n) where n is the battlefield size (~20). Done
         * once per snapshot pass at [SnapshotCapture] entry — not per card.
         * Sources with a null `firstRemembered` are silently skipped here; the
         * per-card path logs them via the `isPrepared && firstRemembered==null`
         * branch in `resolvePreparedRole` so the omission doesn't go unnoticed.
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
