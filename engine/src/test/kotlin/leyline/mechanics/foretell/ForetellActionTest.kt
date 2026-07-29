package leyline.mechanics.foretell

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.buildPriorityActionsForTest
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Foretell hand-activation path (Hand → Exile face-down).
 *
 * Foretell is a hand-keyword whose SA is registered by Forge as a
 * KeywordInstance (`isForetelling=true`), NOT an `AlternativeCost`. Bridge
 * surfaces it via the same hand-cast rail as Plot/Warp/Sneak; the offer
 * carries `alternativeGrpId = FORETELL keyword ability grpId`.
 *
 * Foretell is the only keyword on this rail with a cost mismatch: the hand
 * SA pays {2} (the foretell *action* cost) but the per-card FORETELL row in
 * Arena DB carries the foretell *cast* cost ({R} for Demon Bolt). The
 * mapper compensates with cost-agnostic [findKeywordAbilityGrpId] for
 * foretell, vs cost-aware lookup for everyone else.
 *
 * Cast-from-exile leg uses `AlternativeCost.Foretold` and requires the card
 * to already be in face-down Exile with the foretold flag. That state can't
 * be programmatically synthesized without driving the action, so this file
 * focuses on the hand activation. The exile-cast leg is empirically verified
 * via `puzzles/foretell-demon-bolt.pzl` + bot-match.
 *
 * Card: Demon Bolt (Sorcery R, Foretell {2} / cast for {R} from foretell).
 */
@Suppress(
    "UnnecessaryNotNullOperator",
    // Zone-guard tests assert offer absence via `shouldNot offerAltCost(...)`. The
    // detekt heuristic doesn't recognize custom matchers as equality-shape, but the
    // matcher's failure message names the keyword grpId + counts. Cleaner than the
    // pre-matcher `actionsList.any{}.shouldBeFalse()` either way.
    "WeakAssertionOnly",
)
class ForetellActionTest :
    BoardTest({

        test("Forge surfaces the Foretell hand SA on a hand card (isForetelling=true)") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Demon Bolt", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Demon Bolt" }

            val foretellSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.isForetelling }
            foretellSa shouldNotBe null
        }

        test("ActionMapper offers Cast for foretell card in hand when {2} payable (alternativeGrpId=FORETELL row)") {
            // Foretell action cost is constant {2}. Two Mountains pay it; the per-card
            // FORETELL row carries the *cast* cost {R}. Cost-agnostic lookup must still
            // resolve the offer's alternativeGrpId correctly.
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Demon Bolt", human, ZoneType.Hand)
                }
            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions = buildPriorityActionsForTest(1, b)

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == boltGrpId
                }
            castOffers.shouldNotBeEmpty()
            val foretellOffer = castOffers.firstOrNull { it.alternativeGrpId == foretellAbilityGrpId }
            assertSoftly {
                foretellOffer should beAltCostOffer(foretellAbilityGrpId)
                foretellOffer!!.abilityGrpId shouldBe 0 // alternative rail — abilityGrpId stays 0
            }
        }

        test("buildPriorityActionsForTest offers Cast for foretell card in hand (snapshot path parity)") {
            // Demon Bolt targets "creature or planeswalker". The snapshot path runs
            // hasUnmetTargeting and skips the whole card if the chosen SA has no
            // legal targets — which would also drop the foretell offer. Give the AI
            // a Grizzly Bears so the base SA's targeting is met and the foretell
            // prepared alt-cost branch actually fires.
            val (b, game, _) =
                startWithBoard { _, human, ai ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Demon Bolt", human, ZoneType.Hand)
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }

            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!
            val boltIid = game.humanPlayer.hand.iid("Demon Bolt")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = buildPriorityActionsForTest(1, snap, b)

            val foretellOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == boltIid &&
                        it.alternativeGrpId == foretellAbilityGrpId
                }
            assertSoftly {
                foretellOffer should beAltCostOffer(foretellAbilityGrpId)
                foretellOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("foretell card in hand but only one land → no Cast offer with alternativeGrpId=FORETELL row") {
            // Only one Mountain — can't pay foretell action cost {2}. Base Cast at {R}
            // is payable but carries no alternativeGrpId; the foretell offer must be absent.
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Demon Bolt", human, ZoneType.Hand)
                }
            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions = buildPriorityActionsForTest(1, b)

            actions shouldNot offerAltCost(foretellAbilityGrpId)
        }

        test("foretell card only in graveyard → no Cast offer with alternativeGrpId=FORETELL row") {
            // Foretell hand-action is hand-only. A foretell card in graveyard must not
            // surface a foretell offer.
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Demon Bolt", human, ZoneType.Graveyard)
                }
            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions = buildPriorityActionsForTest(1, b)

            actions shouldNot offerAltCost(foretellAbilityGrpId)
        }
    })
