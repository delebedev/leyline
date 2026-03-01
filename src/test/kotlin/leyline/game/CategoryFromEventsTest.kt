package leyline.game

import forge.game.zone.ZoneType
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

/**
 * Unit tests for [AnnotationBuilder.categoryFromEvents] — verifies that
 * captured [GameEvent] instances resolve to the correct annotation
 * categories, matching the behavior of [AnnotationPipeline.inferCategory]
 * but using rich event data instead of zone-pair heuristics.
 */
@Test(groups = ["unit"])
class CategoryFromEventsTest {

    @Test
    fun landPlayedReturnPlayLand() {
        val events = listOf(
            GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            GameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), TransferCategory.PlayLand)
    }

    @Test
    fun spellCastReturnsCastSpell() {
        val events = listOf(
            GameEvent.SpellCast(forgeCardId = 99, seatId = 1),
            GameEvent.ZoneChanged(forgeCardId = 99, from = ZoneType.Hand, to = ZoneType.Stack),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(99, events), TransferCategory.CastSpell)
    }

    @Test
    fun spellResolvedReturnsResolve() {
        val events = listOf(
            GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
            GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), TransferCategory.Resolve)
    }

    @Test
    fun specificEventTakesPriorityOverZoneChanged() {
        // Both LandPlayed and ZoneChanged fire for the same card.
        // LandPlayed should take priority.
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
            GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), TransferCategory.PlayLand)
    }

    @Test
    fun zoneChangedBattlefieldToGraveyardReturnsDestroy() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Destroy)
    }

    @Test
    fun zoneChangedBattlefieldToExileReturnsExile() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun zoneChangedGraveyardToExileReturnsExile() {
        // GY→Exile now correctly returns Exile (was ZoneTransfer before zone-pair expansion)
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun zoneChangedGenericReturnsZoneTransfer() {
        // Truly generic zone pair that doesn't match any specific category
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Library),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.ZoneTransfer)
    }

    @Test
    fun noMatchingEventReturnsNull() {
        val events = listOf(
            GameEvent.LandPlayed(forgeCardId = 99, seatId = 1),
        )
        assertNull(AnnotationBuilder.categoryFromEvents(42, events))
    }

    @Test
    fun emptyEventsReturnsNull() {
        assertNull(AnnotationBuilder.categoryFromEvents(42, emptyList()))
    }

    @Test
    fun unrelatedEventsIgnored() {
        val events = listOf(
            GameEvent.CardTapped(forgeCardId = 42, tapped = true),
            GameEvent.LifeChanged(seatId = 1, oldLife = 20, newLife = 17),
            GameEvent.DamageDealtToPlayer(sourceForgeId = 10, targetSeatId = 1, amount = 3, combat = true),
        )
        assertNull(AnnotationBuilder.categoryFromEvents(42, events))
    }

    @Test
    fun multipleCardsInEvents() {
        val events = listOf(
            GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            GameEvent.SpellCast(forgeCardId = 99, seatId = 1),
            GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), TransferCategory.PlayLand)
        assertEquals(AnnotationBuilder.categoryFromEvents(99, events), TransferCategory.CastSpell)
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), TransferCategory.Resolve)
        assertNull(AnnotationBuilder.categoryFromEvents(1, events))
    }

    // -- Group A: zone-transition disambiguation --

    @Test
    fun sacrificedOverridesDestroy() {
        // When both CardSacrificed and ZoneChanged(BF→GY) fire, Sacrifice wins
        val events = listOf(
            GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Sacrifice)
    }

    @Test
    fun sacrificedWithoutZoneChangedReturnsNull() {
        // CardSacrificed alone without a ZoneChanged should not produce a category
        val events = listOf(
            GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
        )
        assertNull(AnnotationBuilder.categoryFromEvents(55, events))
    }

    @Test
    fun battlefieldToGraveyardWithoutSacrificeReturnsDestroy() {
        // BF→GY without a CardSacrificed event defaults to Destroy
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Destroy)
    }

    @Test
    fun stackToGraveyardReturnsCountered() {
        // Stack→GY without SpellResolved = countered
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), TransferCategory.Countered)
    }

    @Test
    fun stackToGraveyardWithSpellResolvedReturnsResolve() {
        // Stack→GY with SpellResolved = resolved (e.g. sorcery finishing)
        val events = listOf(
            GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
            GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), TransferCategory.Resolve)
    }

    @Test
    fun battlefieldToHandReturnsBounce() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Hand),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Bounce)
    }

    @Test
    fun battlefieldToLibraryReturnsBounce() {
        // Tuck effects (BF→Library) also use Bounce category per Arena client
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Library),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Bounce)
    }

    @Test
    fun libraryToHandReturnsDraw() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Hand),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Draw)
    }

    @Test
    fun handToGraveyardReturnsDiscard() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Hand, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Discard)
    }

    @Test
    fun libraryToGraveyardReturnsMill() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Mill)
    }

    @Test
    fun libraryToExileReturnsExile() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun handToExileReturnsExile() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Hand, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun stackToExileReturnsExile() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Stack, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun anyToExileFallbackReturnsExile() {
        // Graveyard→Exile uses the catch-all any→Exile rule
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun sacrificedForOtherCardDoesNotAffect() {
        // CardSacrificed for a different card should not affect the target card
        val events = listOf(
            GameEvent.CardSacrificed(forgeCardId = 99, seatId = 1),
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Destroy)
    }

    // -- Group A: zone-specific event variants (enriched ZoneChanged handler) --

    @Test
    fun cardDestroyedReturnsDestroy() {
        // CardDestroyed emitted by enriched ZoneChanged handler for BF→GY
        val events = listOf(
            GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Destroy)
    }

    @Test
    fun cardDestroyedWithSacrificeReturnsSacrifice() {
        // CardSacrificed (from dedicated event) overrides CardDestroyed (from zone pair)
        val events = listOf(
            GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
            GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Sacrifice)
    }

    @Test
    fun cardDestroyedWithSacrificeReverseOrder() {
        // Order shouldn't matter — sacrifice always overrides destroy
        val events = listOf(
            GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
            GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Sacrifice)
    }

    @Test
    fun cardBouncedReturnsBounce() {
        val events = listOf(
            GameEvent.CardBounced(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Bounce)
    }

    @Test
    fun cardExiledReturnsExile() {
        val events = listOf(
            GameEvent.CardExiled(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    @Test
    fun cardDiscardedReturnsDiscard() {
        val events = listOf(
            GameEvent.CardDiscarded(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Discard)
    }

    @Test
    fun cardMilledReturnsMill() {
        val events = listOf(
            GameEvent.CardMilled(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Mill)
    }

    @Test
    fun spellCounteredReturnsCountered() {
        val events = listOf(
            GameEvent.SpellCountered(forgeCardId = 77, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), TransferCategory.Countered)
    }

    @Test
    fun zoneSpecificTakesPriorityOverGenericZoneChanged() {
        // If both a zone-specific event and generic ZoneChanged exist for the
        // same card, the specific event wins (e.g. enriched handler emitted
        // CardDestroyed but a ZoneChanged also leaked through)
        val events = listOf(
            GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Destroy)
    }

    @Test
    fun mechanicEventTakesPriorityOverZoneSpecific() {
        // LandPlayed (mechanic) takes priority even if CardBounced is present
        val events = listOf(
            GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            GameEvent.CardBounced(forgeCardId = 42, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), TransferCategory.PlayLand)
    }

    @Test
    fun sacrificeDoesNotAffectNonDestroyZoneCategory() {
        // CardSacrificed only overrides Destroy, not other zone categories
        val events = listOf(
            GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
            GameEvent.CardExiled(forgeCardId = 55, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }

    // -- Return / Search / Put zone-pair categories --

    @Test
    fun graveyardToHandReturnsReturn() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Hand),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Return)
    }

    @Test
    fun graveyardToBattlefieldReturnsReturn() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Return)
    }

    @Test
    fun exileToHandReturnsReturn() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Hand),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Return)
    }

    @Test
    fun exileToBattlefieldReturnsReturn() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Return)
    }

    @Test
    fun libraryToBattlefieldReturnsSearch() {
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Search)
    }

    @Test
    fun libraryToHandStillReturnsDraw() {
        // Library→Hand should remain Draw, not be overridden to Search
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Hand),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Draw)
    }

    @Test
    fun exileToLibraryReturnsZoneTransfer() {
        // Exile→Library has no specific category
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Library),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.ZoneTransfer)
    }

    @Test
    fun graveyardToExileStillReturnsExile() {
        // GY→Exile should still prefer Exile over Return
        val events = listOf(
            GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), TransferCategory.Exile)
    }
}
