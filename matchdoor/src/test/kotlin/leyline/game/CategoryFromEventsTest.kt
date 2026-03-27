package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

/**
 * Unit tests for [AnnotationBuilder.categoryFromEvents] — verifies that
 * captured [GameEvent] instances resolve to the correct annotation
 * categories, matching the behavior of [AnnotationPipeline.inferCategory]
 * but using rich event data instead of zone-pair heuristics.
 */
class CategoryFromEventsTest :
    FunSpec({

        tags(UnitTag)

        test("landPlayedReturnPlayLand") {
            val events = listOf(
                GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(42), from = Zone.Hand, to = Zone.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events) shouldBe TransferCategory.PlayLand
        }

        test("spellCastReturnsCastSpell") {
            val events = listOf(
                GameEvent.SpellCast(cardId = ForgeCardId(99), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(99), from = Zone.Hand, to = Zone.Stack),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(99), events) shouldBe TransferCategory.CastSpell
        }

        test("spellResolvedReturnsResolve") {
            val events = listOf(
                GameEvent.SpellResolved(cardId = ForgeCardId(77), hasFizzled = false),
                GameEvent.ZoneChanged(cardId = ForgeCardId(77), from = Zone.Stack, to = Zone.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(77), events) shouldBe TransferCategory.Resolve
        }

        test("specificEventTakesPriorityOverZoneChanged") {
            // Both LandPlayed and ZoneChanged fire for the same card.
            // LandPlayed should take priority.
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(42), from = Zone.Hand, to = Zone.Battlefield),
                GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events) shouldBe TransferCategory.PlayLand
        }

        test("zoneChangedBattlefieldToGraveyardReturnsDestroy") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Destroy
        }

        test("zoneChangedBattlefieldToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("zoneChangedGraveyardToExileReturnsExile") {
            // GY→Exile now correctly returns Exile (was ZoneTransfer before zone-pair expansion)
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("zoneChangedGenericReturnsZoneTransfer") {
            // Truly generic zone pair that doesn't match any specific category
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Library),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.ZoneTransfer
        }

        test("noMatchingEventReturnsNull") {
            val events = listOf(
                GameEvent.LandPlayed(cardId = ForgeCardId(99), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events).shouldBeNull()
        }

        test("emptyEventsReturnsNull") {
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), emptyList()).shouldBeNull()
        }

        test("unrelatedEventsIgnored") {
            val events = listOf(
                GameEvent.CardTapped(cardId = ForgeCardId(42), tapped = true),
                GameEvent.LifeChanged(seatId = SeatId(1), oldLife = 20, newLife = 17),
                GameEvent.DamageDealtToPlayer(sourceCardId = ForgeCardId(10), targetSeatId = SeatId(1), amount = 3, combat = true),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events).shouldBeNull()
        }

        test("multipleCardsInEvents") {
            val events = listOf(
                GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)),
                GameEvent.SpellCast(cardId = ForgeCardId(99), seatId = SeatId(1)),
                GameEvent.SpellResolved(cardId = ForgeCardId(77), hasFizzled = false),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events) shouldBe TransferCategory.PlayLand
            AnnotationBuilder.categoryFromEvents(ForgeCardId(99), events) shouldBe TransferCategory.CastSpell
            AnnotationBuilder.categoryFromEvents(ForgeCardId(77), events) shouldBe TransferCategory.Resolve
            AnnotationBuilder.categoryFromEvents(ForgeCardId(1), events).shouldBeNull()
        }

        // -- Group A: zone-transition disambiguation --

        test("sacrificedOverridesDestroy") {
            // When both CardSacrificed and ZoneChanged(BF→GY) fire, Sacrifice wins
            val events = listOf(
                GameEvent.CardSacrificed(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Sacrifice
        }

        test("sacrificedWithoutZoneChangedReturnsNull") {
            // CardSacrificed alone without a ZoneChanged should not produce a category
            val events = listOf(
                GameEvent.CardSacrificed(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events).shouldBeNull()
        }

        test("battlefieldToGraveyardWithoutSacrificeReturnsDestroy") {
            // BF→GY without a CardSacrificed event defaults to Destroy
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Destroy
        }

        test("stackToGraveyardReturnsCountered") {
            // Stack→GY without SpellResolved = countered
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(77), from = Zone.Stack, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(77), events) shouldBe TransferCategory.Countered
        }

        test("stackToGraveyardWithSpellResolvedReturnsResolve") {
            // Stack→GY with SpellResolved = resolved (e.g. sorcery finishing)
            val events = listOf(
                GameEvent.SpellResolved(cardId = ForgeCardId(77), hasFizzled = false),
                GameEvent.ZoneChanged(cardId = ForgeCardId(77), from = Zone.Stack, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(77), events) shouldBe TransferCategory.Resolve
        }

        test("battlefieldToHandReturnsBounce") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Bounce
        }

        test("battlefieldToLibraryReturnsBounce") {
            // Tuck effects (BF→Library) also use Bounce category per Arena client
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Library),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Bounce
        }

        test("libraryToHandReturnsDraw") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Draw
        }

        test("handToGraveyardReturnsDiscard") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Hand, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Discard
        }

        test("libraryToGraveyardReturnsMill") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Mill
        }

        test("libraryToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("handToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Hand, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("stackToExileReturnsExile") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Stack, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("anyToExileFallbackReturnsExile") {
            // Graveyard→Exile uses the catch-all any→Exile rule
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("sacrificedForOtherCardDoesNotAffect") {
            // CardSacrificed for a different card should not affect the target card
            val events = listOf(
                GameEvent.CardSacrificed(cardId = ForgeCardId(99), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Destroy
        }

        // -- Group A: zone-specific event variants (enriched ZoneChanged handler) --

        test("cardDestroyedReturnsDestroy") {
            // CardDestroyed emitted by enriched ZoneChanged handler for BF→GY
            val events = listOf(
                GameEvent.CardDestroyed(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Destroy
        }

        test("cardDestroyedWithSacrificeReturnsSacrifice") {
            // CardSacrificed (from dedicated event) overrides CardDestroyed (from zone pair)
            val events = listOf(
                GameEvent.CardSacrificed(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.CardDestroyed(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Sacrifice
        }

        test("cardDestroyedWithSacrificeReverseOrder") {
            // Order shouldn't matter — sacrifice always overrides destroy
            val events = listOf(
                GameEvent.CardDestroyed(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.CardSacrificed(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Sacrifice
        }

        test("cardBouncedReturnsBounce") {
            val events = listOf(
                GameEvent.CardBounced(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Bounce
        }

        test("cardExiledReturnsExile") {
            val events = listOf(
                GameEvent.CardExiled(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        test("cardDiscardedReturnsDiscard") {
            val events = listOf(
                GameEvent.CardDiscarded(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Discard
        }

        test("cardMilledReturnsMill") {
            val events = listOf(
                GameEvent.CardMilled(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Mill
        }

        test("spellCounteredReturnsCountered") {
            val events = listOf(
                GameEvent.SpellCountered(cardId = ForgeCardId(77), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(77), events) shouldBe TransferCategory.Countered
        }

        test("zoneSpecificTakesPriorityOverGenericZoneChanged") {
            // If both a zone-specific event and generic ZoneChanged exist for the
            // same card, the specific event wins (e.g. enriched handler emitted
            // CardDestroyed but a ZoneChanged also leaked through)
            val events = listOf(
                GameEvent.CardDestroyed(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Battlefield, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Destroy
        }

        test("mechanicEventTakesPriorityOverZoneSpecific") {
            // LandPlayed (mechanic) takes priority even if CardBounced is present
            val events = listOf(
                GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)),
                GameEvent.CardBounced(cardId = ForgeCardId(42), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(42), events) shouldBe TransferCategory.PlayLand
        }

        test("sacrificeDoesNotAffectNonDestroyZoneCategory") {
            // CardSacrificed only overrides Destroy, not other zone categories
            val events = listOf(
                GameEvent.CardSacrificed(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.CardExiled(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        // -- Return / Search / Put zone-pair categories --

        test("graveyardToHandReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Return
        }

        test("graveyardToBattlefieldReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Return
        }

        test("exileToHandReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Exile, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Return
        }

        test("exileToBattlefieldReturnsReturn") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Exile, to = Zone.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Return
        }

        test("libraryToBattlefieldReturnsSearch") {
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Battlefield),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Search
        }

        test("libraryToHandStillReturnsDraw") {
            // Library→Hand should remain Draw, not be overridden to Search
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Draw
        }

        test("exileToLibraryReturnsZoneTransfer") {
            // Exile→Library has no specific category
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Exile, to = Zone.Library),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.ZoneTransfer
        }

        test("graveyardToExileStillReturnsExile") {
            // GY→Exile should still prefer Exile over Return
            val events = listOf(
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Graveyard, to = Zone.Exile),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Exile
        }

        // -- Surveil zone transfer category --

        test("cardSurveiledReturnsSurveil") {
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Surveil
        }

        test("cardSurveiledOverridesGenericLibraryToGraveyard") {
            // CardSurveiled takes priority over ZoneChanged(Library→Graveyard) which would give Mill
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Graveyard),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Surveil
        }

        test("cardSurveiledDoesNotAffectOtherCard") {
            // CardSurveiled for card 55 should not affect card 99's Mill category
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1)),
                GameEvent.CardMilled(cardId = ForgeCardId(99), seatId = SeatId(1)),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Surveil
            AnnotationBuilder.categoryFromEvents(ForgeCardId(99), events) shouldBe TransferCategory.Mill
        }

        test("surveilCategoryLabelIsSurveil") {
            TransferCategory.Surveil.label shouldBe "Surveil"
        }

        // -- Search to hand --

        test("search to hand uses Put category") {
            val events = listOf(GameEvent.CardSearchedToHand(cardId = ForgeCardId(1), sourceCardId = ForgeCardId(2)))
            AnnotationBuilder.categoryFromEvents(ForgeCardId(1), events) shouldBe TransferCategory.Put
        }

        test("searchToHandOverridesLibraryToHandDraw") {
            // CardSearchedToHand overrides generic ZoneChanged(Library→Hand) which would give Draw
            val events = listOf(
                GameEvent.CardSearchedToHand(cardId = ForgeCardId(55), sourceCardId = ForgeCardId(10)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(55), from = Zone.Library, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Put
        }

        test("searchToHandDoesNotAffectOtherCard") {
            val events = listOf(
                GameEvent.CardSearchedToHand(cardId = ForgeCardId(55), sourceCardId = ForgeCardId(10)),
                GameEvent.ZoneChanged(cardId = ForgeCardId(99), from = Zone.Library, to = Zone.Hand),
            )
            AnnotationBuilder.categoryFromEvents(ForgeCardId(55), events) shouldBe TransferCategory.Put
            AnnotationBuilder.categoryFromEvents(ForgeCardId(99), events) shouldBe TransferCategory.Draw
        }

        // -- affectorSourceFromEvents --

        test("affectorSourceReturnsSurveilSourceCard") {
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1), sourceCardId = ForgeCardId(42)),
            )
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(55), events) shouldBe ForgeCardId(42)
        }

        test("affectorSourceReturnsMillSourceCard") {
            val events = listOf(
                GameEvent.CardMilled(cardId = ForgeCardId(55), seatId = SeatId(1), sourceCardId = ForgeCardId(42)),
            )
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(55), events) shouldBe ForgeCardId(42)
        }

        test("affectorSourceReturnsNullWhenMillHasNoSource") {
            val events = listOf(
                GameEvent.CardMilled(cardId = ForgeCardId(55), seatId = SeatId(1), sourceCardId = null),
            )
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(55), events).shouldBeNull()
        }

        test("affectorSourceReturnsNullWhenSurveilHasNoSource") {
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1), sourceCardId = null),
            )
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(55), events).shouldBeNull()
        }

        test("affectorSourceMatchesCorrectCard") {
            val events = listOf(
                GameEvent.CardSurveiled(cardId = ForgeCardId(55), seatId = SeatId(1), sourceCardId = ForgeCardId(42)),
                GameEvent.CardSurveiled(cardId = ForgeCardId(66), seatId = SeatId(1), sourceCardId = ForgeCardId(43)),
            )
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(55), events) shouldBe ForgeCardId(42)
            AnnotationBuilder.affectorSourceFromEvents(ForgeCardId(66), events) shouldBe ForgeCardId(43)
        }
    })
