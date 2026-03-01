package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [AnnotationBuilder.categoryFromEvents] — verifies that
 * captured [GameEvent] instances resolve to the correct annotation
 * categories, matching the behavior of [AnnotationPipeline.inferCategory]
 * but using rich event data instead of zone-pair heuristics.
 */
class CategoryFromEventsTest :
    FunSpec({

        test("landPlayedReturnPlayLand") {
            val events = listOf(
                GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
                GameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(42, events) shouldBe TransferCategory.PlayLand
        }

        test("spellCastReturnsCastSpell") {
            val events = listOf(
                GameEvent.SpellCast(forgeCardId = 99, seatId = 1),
                GameEvent.ZoneChanged(forgeCardId = 99, from = ZoneType.Hand, to = ZoneType.Stack),
            )
            AnnotationBuilder.categoryFromEvents(99, events) shouldBe TransferCategory.CastSpell
        }

        test("spellResolvedReturnsResolve") {
            val events = listOf(
                GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
                GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(77, events) shouldBe TransferCategory.Resolve
        }

        test("specificEventTakesPriorityOverZoneChanged") {
            // Both LandPlayed and ZoneChanged fire for the same card.
            // LandPlayed should take priority.
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
                GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(42, events) shouldBe TransferCategory.PlayLand
        }

        test("zoneChangedBattlefieldToGraveyardReturnsDestroy") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Destroy
        }

        test("zoneChangedBattlefieldToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("zoneChangedGraveyardToExileReturnsExile") {
            // GY→Exile now correctly returns Exile (was ZoneTransfer before zone-pair expansion)
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("zoneChangedGenericReturnsZoneTransfer") {
            // Truly generic zone pair that doesn't match any specific category
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Library),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.ZoneTransfer
        }

        test("noMatchingEventReturnsNull") {
            val events = listOf(
                GameEvent.LandPlayed(forgeCardId = 99, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(42, events).shouldBeNull()
        }

        test("emptyEventsReturnsNull") {
            AnnotationBuilder.categoryFromEvents(42, emptyList()).shouldBeNull()
        }

        test("unrelatedEventsIgnored") {
            val events = listOf(
                GameEvent.CardTapped(forgeCardId = 42, tapped = true),
                GameEvent.LifeChanged(seatId = 1, oldLife = 20, newLife = 17),
                GameEvent.DamageDealtToPlayer(sourceForgeId = 10, targetSeatId = 1, amount = 3, combat = true),
            )
            AnnotationBuilder.categoryFromEvents(42, events).shouldBeNull()
        }

        test("multipleCardsInEvents") {
            val events = listOf(
                GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
                GameEvent.SpellCast(forgeCardId = 99, seatId = 1),
                GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
            )
            AnnotationBuilder.categoryFromEvents(42, events) shouldBe TransferCategory.PlayLand
            AnnotationBuilder.categoryFromEvents(99, events) shouldBe TransferCategory.CastSpell
            AnnotationBuilder.categoryFromEvents(77, events) shouldBe TransferCategory.Resolve
            AnnotationBuilder.categoryFromEvents(1, events).shouldBeNull()
        }

        // -- Group A: zone-transition disambiguation --

        test("sacrificedOverridesDestroy") {
            // When both CardSacrificed and ZoneChanged(BF→GY) fire, Sacrifice wins
            val events = listOf(
                GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Sacrifice
        }

        test("sacrificedWithoutZoneChangedReturnsNull") {
            // CardSacrificed alone without a ZoneChanged should not produce a category
            val events = listOf(
                GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events).shouldBeNull()
        }

        test("battlefieldToGraveyardWithoutSacrificeReturnsDestroy") {
            // BF→GY without a CardSacrificed event defaults to Destroy
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Destroy
        }

        test("stackToGraveyardReturnsCountered") {
            // Stack→GY without SpellResolved = countered
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(77, events) shouldBe TransferCategory.Countered
        }

        test("stackToGraveyardWithSpellResolvedReturnsResolve") {
            // Stack→GY with SpellResolved = resolved (e.g. sorcery finishing)
            val events = listOf(
                GameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
                GameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(77, events) shouldBe TransferCategory.Resolve
        }

        test("battlefieldToHandReturnsBounce") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Hand),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Bounce
        }

        test("battlefieldToLibraryReturnsBounce") {
            // Tuck effects (BF→Library) also use Bounce category per Arena client
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Library),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Bounce
        }

        test("libraryToHandReturnsDraw") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Hand),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Draw
        }

        test("handToGraveyardReturnsDiscard") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Hand, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Discard
        }

        test("libraryToGraveyardReturnsMill") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Mill
        }

        test("libraryToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("handToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Hand, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("stackToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Stack, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("anyToExileFallbackReturnsExile") {
            // Graveyard→Exile uses the catch-all any→Exile rule
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("sacrificedForOtherCardDoesNotAffect") {
            // CardSacrificed for a different card should not affect the target card
            val events = listOf(
                GameEvent.CardSacrificed(forgeCardId = 99, seatId = 1),
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Destroy
        }

        // -- Group A: zone-specific event variants (enriched ZoneChanged handler) --

        test("cardDestroyedReturnsDestroy") {
            // CardDestroyed emitted by enriched ZoneChanged handler for BF→GY
            val events = listOf(
                GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Destroy
        }

        test("cardDestroyedWithSacrificeReturnsSacrifice") {
            // CardSacrificed (from dedicated event) overrides CardDestroyed (from zone pair)
            val events = listOf(
                GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
                GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Sacrifice
        }

        test("cardDestroyedWithSacrificeReverseOrder") {
            // Order shouldn't matter — sacrifice always overrides destroy
            val events = listOf(
                GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
                GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Sacrifice
        }

        test("cardBouncedReturnsBounce") {
            val events = listOf(
                GameEvent.CardBounced(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Bounce
        }

        test("cardExiledReturnsExile") {
            val events = listOf(
                GameEvent.CardExiled(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        test("cardDiscardedReturnsDiscard") {
            val events = listOf(
                GameEvent.CardDiscarded(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Discard
        }

        test("cardMilledReturnsMill") {
            val events = listOf(
                GameEvent.CardMilled(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Mill
        }

        test("spellCounteredReturnsCountered") {
            val events = listOf(
                GameEvent.SpellCountered(forgeCardId = 77, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(77, events) shouldBe TransferCategory.Countered
        }

        test("zoneSpecificTakesPriorityOverGenericZoneChanged") {
            // If both a zone-specific event and generic ZoneChanged exist for the
            // same card, the specific event wins (e.g. enriched handler emitted
            // CardDestroyed but a ZoneChanged also leaked through)
            val events = listOf(
                GameEvent.CardDestroyed(forgeCardId = 55, seatId = 1),
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Destroy
        }

        test("mechanicEventTakesPriorityOverZoneSpecific") {
            // LandPlayed (mechanic) takes priority even if CardBounced is present
            val events = listOf(
                GameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
                GameEvent.CardBounced(forgeCardId = 42, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(42, events) shouldBe TransferCategory.PlayLand
        }

        test("sacrificeDoesNotAffectNonDestroyZoneCategory") {
            // CardSacrificed only overrides Destroy, not other zone categories
            val events = listOf(
                GameEvent.CardSacrificed(forgeCardId = 55, seatId = 1),
                GameEvent.CardExiled(forgeCardId = 55, seatId = 1),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }

        // -- Return / Search / Put zone-pair categories --

        test("graveyardToHandReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Hand),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Return
        }

        test("graveyardToBattlefieldReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Return
        }

        test("exileToHandReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Hand),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Return
        }

        test("exileToBattlefieldReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Return
        }

        test("libraryToBattlefieldReturnsSearch") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Search
        }

        test("libraryToHandStillReturnsDraw") {
            // Library→Hand should remain Draw, not be overridden to Search
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Library, to = ZoneType.Hand),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Draw
        }

        test("exileToLibraryReturnsZoneTransfer") {
            // Exile→Library has no specific category
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Exile, to = ZoneType.Library),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.ZoneTransfer
        }

        test("graveyardToExileStillReturnsExile") {
            // GY→Exile should still prefer Exile over Return
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
            )
            AnnotationBuilder.categoryFromEvents(55, events) shouldBe TransferCategory.Exile
        }
    })
