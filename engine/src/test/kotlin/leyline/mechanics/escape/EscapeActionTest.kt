package leyline.mechanics.escape

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost

/**
 * Escape graveyard-cast-with-alternate-cost path — zone and additional-cost guards.
 *
 * Escape is a graveyard alt-cost keyword: cast a card from your graveyard for
 * mana plus an additional cost (exile N other cards from your graveyard).
 * Forge registers the SA via `K:Escape:<cost>|Type:Card|N:<count>` with
 * `setAlternativeCost(AlternativeCost.Escape)`.
 *
 * The Forge-surfaces and ActionMapper-offers-Cast happy-path tests for Escape
 * live in `leyline.mechanics.AltCostOfferTest` alongside the other alt-cost
 * keywords. This file keeps only the guards specific to Escape's additional
 * cost (N other cards exiled from graveyard).
 *
 * Card: Glimpse of Freedom (Instant {U}, "Draw a card.", Escape {2}{U}+exile-5).
 */
class EscapeActionTest :
    BoardTest({

        test("escape card only in hand → no graveyard-cast offer (zone guard)") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Glimpse of Freedom", human, ZoneType.Hand)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val glimpseGrpId = b.cardRepository.findGrpIdByName("Glimpse of Freedom")!!
            val escapeAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(glimpseGrpId, KeywordAbilityIds.ESCAPE)!!

            // Glimpse from hand surfaces only the regular Cast SA, not Escape.
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Glimpse of Freedom" }
            val handEscapeSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Escape }
            handEscapeSa shouldBe null

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            fromSnap shouldNot offerAltCost(escapeAbilityGrpId)
        }

        test("escape card in graveyard but only 3 other GY cards → no Escape offer (additional cost not payable)") {
            // Glimpse needs 5 other GY cards exiled. With only 3 others, Forge's canPlay
            // should fail the additional-cost check and the Escape SA should not surface.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                    addCard("Plains", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Glimpse of Freedom" }

            val escapeSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Escape }
            // Forge's getAlternativeCosts gates on canPlay, which checks payAdditionalCosts.
            // With 3 others < 5 required, the escape SA should not be castable.
            escapeSa shouldBe null

            val glimpseGrpId = b.cardRepository.findGrpIdByName("Glimpse of Freedom")!!
            val escapeAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(glimpseGrpId, KeywordAbilityIds.ESCAPE)!!

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            fromSnap shouldNot offerAltCost(escapeAbilityGrpId)
        }
    })
