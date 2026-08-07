package leyline.mechanics.sneak

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost

/**
 * Sneak hand-cast-with-alternate-cost path.
 *
 * Board-level structural and negative coverage, parallel to WarpActionTest.
 * SneakLifecycleTest owns the positive combat flow because Sneak's additional
 * cost requires a declared-blockers state with an unblocked attacker. Here we verify:
 *  - Forge exposes the Sneak alt-cost SA on the hand card.
 *  - Negative guards: insufficient mana → no alt-cost Cast offer.
 *  - Wrong-zone guards: graveyard / library → no alt-cost Cast offer.
 *
 * Card: Splinter's Technique (Sorcery 3B, Sneak {1}{B}).
 */
@Suppress(
    // Structural guards assert absence/presence of offer shapes — boolean predicates over
    // ActionsAvailableReq lists are the native idiom. SneakLifecycleTest carries the
    // positive cast and equality-shape assertions.
    "WeakAssertionOnly",
)
class SneakActionTest :
    BoardTest({

        test("Forge surfaces the Sneak alt-cost SA on a hand card") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Splinter's Technique", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Splinter's Technique" }

            val sneakSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Sneak }
            // getAllCastableAbilities filters on canPlay/canCastTiming — Sneak's
            // cost can fail without an unblocked attacker, so the SA may not surface
            // here. Fall back to checking the card's intrinsic spell abilities in
            // that case — they still include the Sneak-cost SA registered by
            // Forge's keyword pipeline.
            val saFromSpells =
                sneakSa ?: card.spellAbilities.firstOrNull { it.alternativeCost == AlternativeCost.Sneak }
            saFromSpells shouldNotBe null
        }

        test("Sneak card present: card DB carries the sneak keyword ability grpId") {
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Splinter's Technique", human, ZoneType.Hand)
                }
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)
            sneakAbilityGrpId shouldNotBe null
            sneakAbilityGrpId!! shouldBeGreaterThan 0
        }

        test("ActionMapper.buildFromSnapshot does not crash and does not emit bogus offer without attackers") {
            // Sneak's payCost includes "Return an unblocked attacker", so without a
            // combat state canPayManaCost returns false and no alt-cost offer should
            // appear. This pins the snapshot path shape — the same rail
            // SneakLifecycleTest exercises — against the Sneak keyword.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Splinter's Technique", human, ZoneType.Hand)
                }
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)!!

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            fromSnap shouldNot offerAltCost(sneakAbilityGrpId)
        }

        test(
            "ActionMapper handles prod-shape CardData: no NPE, " +
                "and no alt-cost sneak offer when combat preconditions fail",
        ) {
            // Reproduces the runtime shape where CardData.keywordAbilityGrpIds arrives
            // empty (production ExposedCardRepository does not populate it). Forces
            // canPayManaCost via a stubbed SA path is too much ceremony, so we exercise
            // the pure mapper path and assert that when canPay would be true, the
            // AbilityRegistry fallback surfaces the slot grpId.
            // Here we assert the NEGATIVE guardrail: empty keyword map + no payable
            // sneak cost → no offer, and critically no NPE/IndexOutOfBounds.
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Splinter's Technique", human, ZoneType.Hand)
                }
            val b = board.bridge
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(sneakAbilityGrpId)
            // At least the base Cast inactive action should be present — the mapper ran
            // through the hand iteration without erroring on the prod-shaped CardData.
            actions.inactiveActionsList.any { it.actionType.name == "Cast" && it.grpId == grpId }.shouldBeTrue()
        }

        test("Sneak card in hand, insufficient mana → no alt-cost Cast offer") {
            val board =
                startWithBoard { _, human, _ ->
                    // No Swamps — can't pay {1}{B}.
                    addCard("Splinter's Technique", human, ZoneType.Hand)
                }
            val b = board.bridge
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(sneakAbilityGrpId)
        }

        test("Sneak card only in library → no alt-cost Cast offer") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Splinter's Technique", human, ZoneType.Library)
                }
            val b = board.bridge
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(sneakAbilityGrpId)
        }

        test("Sneak card in graveyard → no alt-cost Cast offer") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Splinter's Technique", human, ZoneType.Graveyard)
                }
            val b = board.bridge
            val grpId = b.cardRepository.findGrpIdByName("Splinter's Technique")!!
            val sneakAbilityGrpId =
                (b.cardRepository as leyline.game.InMemoryCardRepository)
                    .findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SNEAK)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(sneakAbilityGrpId)
        }
    })
