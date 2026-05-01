package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.game.event.GameEvent
import leyline.game.event.Zone

/**
 * One row in the transfer-category dispatch table. A rule's [match] is a
 * predicate over the event list scoped to a single forge card; the
 * highest-priority matching rule's [category] wins.
 *
 * Rules express the layers of the original branch tree:
 *  - **Mechanic short-circuit** (priority 100): card is the subject of a
 *    `LandPlayed` / `SpellCast` / `SpellResolved` / `LegendRuleDeath` event.
 *  - **Operation supersedes outcome** (95): `CardSurveiled` outranks the
 *    `CardMilled` that `Player.surveil()` emits as a side-effect on the same
 *    card.
 *  - **Zone-specific events** (90): enriched `Card*` / `Spell*` events emitted
 *    by [leyline.game.event.GameEventCollector] alongside `ZoneChanged`.
 *  - **Sacrifice override** (85, 70): `CardSacrificed` promotes a `Destroy` /
 *    BF→GY transfer (and any other `ZoneChanged`) to `Sacrifice`. The 85 row
 *    outranks the plain `Destroy` rule (80); the 70 row outranks the zone-pair
 *    fallback (60).
 *  - **CardDestroyed** (80): bare `CardDestroyed` without sacrifice context.
 *  - **Zone-pair fallback** (60, 55): generic `ZoneChanged` resolved by
 *    `(from, to)` lookup; 55 catches "anything → Exile" with an unhandled
 *    source zone.
 *  - **Catch-all** (50): a `ZoneChanged` fired but no specific pair matched —
 *    return `ZoneTransfer`.
 *
 * Adding a new category = add a row.
 */
internal data class CategoryRule(
    val priority: Int,
    val category: TransferCategory,
    val match: (events: List<GameEvent>, forgeCardId: ForgeCardId) -> Boolean,
)

internal object CategoryRules {
    private fun List<GameEvent>.hasZoneChanged(
        forgeCardId: ForgeCardId,
        from: Zone,
        to: Zone,
    ): Boolean = any { it is GameEvent.ZoneChanged && it.cardId == forgeCardId && it.from == from && it.to == to }

    private fun List<GameEvent>.hasZoneChangedTo(
        forgeCardId: ForgeCardId,
        to: Zone,
    ): Boolean = any { it is GameEvent.ZoneChanged && it.cardId == forgeCardId && it.to == to }

    private fun List<GameEvent>.hasZoneChangedFor(forgeCardId: ForgeCardId): Boolean =
        any { it is GameEvent.ZoneChanged && it.cardId == forgeCardId }

    private fun zonePairRule(
        from: Zone,
        to: Zone,
        category: TransferCategory,
    ): CategoryRule =
        CategoryRule(
            priority = 60,
            category = category,
            match = { events, fid -> events.hasZoneChanged(fid, from, to) },
        )

    /**
     * Priority-sorted rule table. `firstOrNull` over this list picks the
     * winner; equal-priority rules don't overlap on the same `(forgeCardId,
     * events)` input by construction.
     */
    val all: List<CategoryRule> =
        listOf(
            // ── Priority 100: mechanic short-circuit ─────────────────────
            CategoryRule(100, TransferCategory.PlayLand) { events, fid ->
                events.any { it is GameEvent.LandPlayed && it.cardId == fid }
            },
            CategoryRule(100, TransferCategory.CastSpell) { events, fid ->
                events.any { it is GameEvent.SpellCast && it.cardId == fid && !it.isTrigger }
            },
            CategoryRule(100, TransferCategory.Resolve) { events, fid ->
                events.any { it is GameEvent.SpellResolved && it.cardId == fid && !it.hasFizzled }
            },
            CategoryRule(100, TransferCategory.SbaLegendRule) { events, fid ->
                events.any { it is GameEvent.LegendRuleDeath && it.cardId == fid }
            },
            // ── Priority 95: operation events that supersede an outcome-equivalent ──
            //   Surveil supersedes Mill — `Player.surveil()` fires
            //   `CardMilled` (from `moveToGraveyard`) immediately followed by
            //   `CardSurveiled`. Both apply to the same card; Surveil is the
            //   more specific operation and wins.
            CategoryRule(95, TransferCategory.Surveil) { events, fid ->
                events.any { it is GameEvent.CardSurveiled && it.cardId == fid }
            },
            // ── Priority 90: zone-specific events (enriched ZoneChanged variants) ─
            CategoryRule(90, TransferCategory.Exile) { events, fid ->
                events.any { it is GameEvent.CardExiled && it.cardId == fid }
            },
            CategoryRule(90, TransferCategory.Bounce) { events, fid ->
                events.any { it is GameEvent.CardBounced && it.cardId == fid }
            },
            CategoryRule(90, TransferCategory.Discard) { events, fid ->
                events.any { it is GameEvent.CardDiscarded && it.cardId == fid }
            },
            CategoryRule(90, TransferCategory.Mill) { events, fid ->
                events.any { it is GameEvent.CardMilled && it.cardId == fid }
            },
            CategoryRule(90, TransferCategory.Put) { events, fid ->
                events.any { it is GameEvent.CardSearchedToHand && it.cardId == fid }
            },
            CategoryRule(90, TransferCategory.Countered) { events, fid ->
                events.any { it is GameEvent.SpellCountered && it.cardId == fid } ||
                    events.any { it is GameEvent.SpellResolved && it.cardId == fid && it.hasFizzled }
            },
            // ── Priority 85: sacrifice promotes Destroy → Sacrifice ─────
            //   CardSacrificed alongside a Destroy signal (CardDestroyed
            //   event, or a bare BF→GY ZoneChanged) wins over the plain
            //   Destroy rule below.
            CategoryRule(85, TransferCategory.Sacrifice) { events, fid ->
                val sacrificed = events.any { it is GameEvent.CardSacrificed && it.cardId == fid }
                if (!sacrificed) {
                    false
                } else {
                    events.any { it is GameEvent.CardDestroyed && it.cardId == fid } ||
                        events.hasZoneChanged(fid, Zone.Battlefield, Zone.Graveyard)
                }
            },
            // ── Priority 80: bare CardDestroyed → Destroy ───────────────
            CategoryRule(80, TransferCategory.Destroy) { events, fid ->
                events.any { it is GameEvent.CardDestroyed && it.cardId == fid }
            },
            // ── Priority 70: sacrifice + any ZoneChanged → Sacrifice ────
            //   Catches the "BF→Hand bounce with sacrifice flag" and any
            //   other zone-pair where CardSacrificed fires alongside a bare
            //   ZoneChanged (legacy: pre-enriched-handler events).
            CategoryRule(70, TransferCategory.Sacrifice) { events, fid ->
                events.any { it is GameEvent.CardSacrificed && it.cardId == fid } &&
                    events.hasZoneChangedFor(fid)
            },
            // ── Priority 60: zone-pair fallback ─────────────────────────
            zonePairRule(Zone.Hand, Zone.Battlefield, TransferCategory.PlayLand),
            zonePairRule(Zone.Hand, Zone.Stack, TransferCategory.CastSpell),
            zonePairRule(Zone.Hand, Zone.Graveyard, TransferCategory.Discard),
            zonePairRule(Zone.Hand, Zone.Exile, TransferCategory.Exile),
            zonePairRule(Zone.Stack, Zone.Battlefield, TransferCategory.Resolve),
            zonePairRule(Zone.Stack, Zone.Graveyard, TransferCategory.Countered),
            zonePairRule(Zone.Stack, Zone.Exile, TransferCategory.Exile),
            zonePairRule(Zone.Battlefield, Zone.Graveyard, TransferCategory.Destroy),
            zonePairRule(Zone.Battlefield, Zone.Exile, TransferCategory.Exile),
            zonePairRule(Zone.Battlefield, Zone.Hand, TransferCategory.Bounce),
            zonePairRule(Zone.Battlefield, Zone.Library, TransferCategory.Bounce),
            zonePairRule(Zone.Library, Zone.Hand, TransferCategory.Draw),
            zonePairRule(Zone.Library, Zone.Battlefield, TransferCategory.Search),
            zonePairRule(Zone.Library, Zone.Graveyard, TransferCategory.Mill),
            zonePairRule(Zone.Library, Zone.Exile, TransferCategory.Exile),
            zonePairRule(Zone.Graveyard, Zone.Hand, TransferCategory.Return),
            zonePairRule(Zone.Graveyard, Zone.Battlefield, TransferCategory.Return),
            zonePairRule(Zone.Graveyard, Zone.Exile, TransferCategory.Exile),
            zonePairRule(Zone.Exile, Zone.Hand, TransferCategory.Return),
            zonePairRule(Zone.Exile, Zone.Battlefield, TransferCategory.Return),
            // Exile→GY: declined Madness — card exits exile to owner's GY without being cast.
            zonePairRule(Zone.Exile, Zone.Graveyard, TransferCategory.Put),
            // ── Priority 55: any source → Exile catch-all ──────────────
            //   Covers Command→Exile / Other→Exile that aren't enumerated above.
            CategoryRule(55, TransferCategory.Exile) { events, fid ->
                events.hasZoneChangedTo(fid, Zone.Exile)
            },
            // ── Priority 50: bare ZoneChanged catch-all → ZoneTransfer ──
            CategoryRule(50, TransferCategory.ZoneTransfer) { events, fid ->
                events.hasZoneChangedFor(fid)
            },
        ).sortedByDescending { it.priority }
}

/**
 * Resolves [TransferCategory] and transfer affectors from a list of [GameEvent]s.
 *
 * Bridges Forge's event model to the client's annotation categories. Picks the
 * highest-priority matching rule from [CategoryRules.all] — adding a new
 * category is one row in that table.
 *
 * @see AnnotationBuilder for annotation construction
 * @see ZoneTransferDetector for zone transfer detection (the primary caller)
 * @see GameEvent for the Forge→protocol event translation layer
 * @see TransferCategory for the category label enum
 */
object TransferCategoryResolver {
    /**
     * Resolve the annotation category for a zone transfer from the frame's events.
     *
     * Returns null when no rule matches — caller should fall back to
     * [ZoneTransferDetector.inferCategory].
     */
    fun categoryFromEvents(
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
    ): TransferCategory? = CategoryRules.all.firstOrNull { it.match(events, forgeCardId) }?.category

    /**
     * Extract the source Forge card ID for the ability that caused a zone transfer.
     *
     * Used to resolve the affectorId on annotations. Currently only CardSurveiled
     * carries source info; extend for other mechanics as needed.
     *
     * @return Forge card ID of the causing ability's host card, or null if unknown.
     */
    fun affectorSourceFromEvents(
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
    ): ForgeCardId? {
        for (ev in events) {
            when {
                ev is GameEvent.CardMilled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardSurveiled && ev.cardId == forgeCardId -> return ev.sourceCardId
                ev is GameEvent.CardDestroyed && ev.cardId == forgeCardId -> return ev.sourceCardId
            }
        }
        return null
    }
}
