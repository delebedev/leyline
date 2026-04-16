package leyline.game

import leyline.bridge.ForgeCardId

/**
 * Resolves [TransferCategory] and transfer affectors from captured [GameEvent]s.
 *
 * Bridges Forge's event model to client's annotation categories — picks the
 * most specific category for a zone transfer (LandPlayed > ZoneChanged) and
 * extracts the source card that caused it when applicable.
 *
 * @see AnnotationBuilder for annotation construction
 * @see ZoneTransferDetector for zone transfer detection (the primary caller)
 * @see GameEvent for the Forge→protocol event translation layer
 * @see TransferCategory for the category label enum
 */
object TransferCategoryResolver {

    /**
     * Resolve the annotation category for a zone transfer using captured events.
     *
     * Looks up the forge card ID in the event list and returns the category
     * based on the **most specific** event (LandPlayed > ZoneChanged, etc.).
     * Returns null if no matching event was found — caller should fall back
     * to [ZoneTransferDetector.inferCategory].
     *
     * Priority: specific mechanic events > CardSacrificed override > zone-pair inference.
     */
    @Suppress("CyclomaticComplexMethod") // flat dispatch table, not actual complexity
    fun categoryFromEvents(forgeCardId: ForgeCardId, events: List<GameEvent>): TransferCategory? {
        var generic: GameEvent.ZoneChanged? = null
        var sacrificed = false
        var zoneCategory: TransferCategory? = null

        for (ev in events) {
            when (ev) {
                // Highest priority — mechanic-specific events (immediate return).
                // TODO: scope SpellCast by dstZone==Stack to avoid tagging Madness's
                // Hand→Exile discard-replacement transfer as CastSpell. Blocked on
                // a fallback for Exile→Stack alt-cost correlation when the
                // SpellCast event lands in a separate GSM cycle from the zone
                // transfer (Forge PlayEffect-driven casts). See
                // docs/protocol/mechanics/Madness.md § Wiring assessment.
                is GameEvent.LandPlayed -> if (ev.cardId == forgeCardId) return TransferCategory.PlayLand
                is GameEvent.SpellCast -> if (ev.cardId == forgeCardId) return TransferCategory.CastSpell
                is GameEvent.SpellResolved -> if (ev.cardId == forgeCardId) {
                    // Fizzled spells (countered) go Stack→GY — not a successful resolve
                    if (ev.hasFizzled) {
                        zoneCategory = TransferCategory.Countered
                    } else {
                        return TransferCategory.Resolve
                    }
                }
                // Legend rule SBA — highest zone-specific priority (immediate return)
                is GameEvent.LegendRuleDeath -> if (ev.cardId == forgeCardId) return TransferCategory.SbaLegendRule
                // Sacrifice flag — overrides Destroy when both fire for same card
                is GameEvent.CardSacrificed -> if (ev.cardId == forgeCardId) sacrificed = true
                // Zone-specific events (emitted by enriched ZoneChanged handler)
                is GameEvent.CardDestroyed -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Destroy
                is GameEvent.CardBounced -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Bounce
                is GameEvent.CardExiled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Exile
                is GameEvent.CardDiscarded -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Discard
                is GameEvent.CardMilled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Mill
                is GameEvent.CardSurveiled -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Surveil
                is GameEvent.CardSearchedToHand -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Put
                is GameEvent.SpellCountered -> if (ev.cardId == forgeCardId) zoneCategory = TransferCategory.Countered
                // Generic zone change — fallback, infer category from zone pair
                is GameEvent.ZoneChanged -> if (ev.cardId == forgeCardId) generic = ev
                // Other events (tapped, damage, life, counters, etc.) don't affect transfer category
                else -> {}
            }
        }

        // Zone-specific events take priority over generic ZoneChanged
        if (zoneCategory != null) {
            // CardSacrificed overrides CardDestroyed (BF→GY) when both fire
            return if (sacrificed && zoneCategory == TransferCategory.Destroy) {
                TransferCategory.Sacrifice
            } else {
                zoneCategory
            }
        }

        // Fallback: generic ZoneChanged → zone-pair heuristic
        return when {
            generic != null && sacrificed -> TransferCategory.Sacrifice
            generic != null -> zoneChangedCategory(generic)
            else -> null
        }
    }

    /**
     * Extract the source Forge card ID for the ability that caused a zone transfer.
     *
     * Used to resolve the affectorId on annotations. Currently only CardSurveiled
     * carries source info; extend for other mechanics as needed.
     *
     * @return Forge card ID of the causing ability's host card, or null if unknown.
     */
    fun affectorSourceFromEvents(forgeCardId: ForgeCardId, events: List<GameEvent>): ForgeCardId? {
        for (ev in events) {
            when {
                ev is GameEvent.CardMilled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardSurveiled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardDestroyed && ev.cardId == forgeCardId -> return ev.sourceCardId
            }
        }
        return null
    }

    /**
     * Map a generic ZoneChanged event to an annotation category using zone-pair heuristics.
     *
     * This covers Group A categories that lack dedicated Forge events:
     * Destroy (BF→GY), Bounce (BF→Hand), Draw (Lib→Hand), Discard (Hand→GY),
     * Mill (Lib→GY), Countered (Stack→GY), and Exile (any→Exile).
     */
    @Suppress("CyclomaticComplexMethod") // flat zone-pair dispatch, not actual complexity
    private fun zoneChangedCategory(ev: GameEvent.ZoneChanged): TransferCategory = when {
        ev.from == Zone.Hand -> when (ev.to) {
            Zone.Battlefield -> TransferCategory.PlayLand
            Zone.Stack -> TransferCategory.CastSpell
            Zone.Graveyard -> TransferCategory.Discard
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Stack -> when (ev.to) {
            Zone.Battlefield -> TransferCategory.Resolve
            Zone.Graveyard -> TransferCategory.Countered
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Battlefield -> when (ev.to) {
            Zone.Graveyard -> TransferCategory.Destroy
            Zone.Exile -> TransferCategory.Exile
            Zone.Hand -> TransferCategory.Bounce
            Zone.Library -> TransferCategory.Bounce
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Library -> when (ev.to) {
            Zone.Hand -> TransferCategory.Draw
            Zone.Battlefield -> TransferCategory.Search
            Zone.Graveyard -> TransferCategory.Mill
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Graveyard -> when (ev.to) {
            Zone.Hand, Zone.Battlefield -> TransferCategory.Return
            Zone.Exile -> TransferCategory.Exile
            else -> TransferCategory.ZoneTransfer
        }
        ev.from == Zone.Exile -> when (ev.to) {
            Zone.Hand, Zone.Battlefield -> TransferCategory.Return
            // Exile → Graveyard. Primary case: declined Madness — the madness
            // ability resolves without the player electing to cast, so the card
            // exits exile to its owner's graveyard. Tag as `Put`. Generic
            // enough to also cover cleanup of an exiled card moving to graveyard.
            Zone.Graveyard -> TransferCategory.Put
            else -> TransferCategory.ZoneTransfer
        }
        ev.to == Zone.Exile -> TransferCategory.Exile
        else -> TransferCategory.ZoneTransfer
    }
}
