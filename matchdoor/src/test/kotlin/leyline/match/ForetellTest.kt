package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.SnapshotCapture
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
    "MissingAssertSoftly",
    "UnnecessaryNotNullOperator",
    // Zone-guard tests assert the absence of an offer — boolean predicates on
    // the action list are the native idiom (no equality-shape to assert).
    "WeakAssertionOnly",
)
class ForetellTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Forge surfaces the Foretell hand SA on a hand card (isForetelling=true)") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Demon Bolt", human, ZoneType.Hand)
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
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Demon Bolt", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                    cardRepository = b.cardRepository,
                )

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == boltGrpId
                }
            castOffers.shouldNotBeEmpty()
            val foretellOffer = castOffers.firstOrNull { it.alternativeGrpId == foretellAbilityGrpId }
            assertSoftly(foretellOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == foretellAbilityGrpId }.shouldBeTrue()
            }
        }

        test("ActionMapper.buildFromSnapshot offers Cast for foretell card in hand (snapshot path parity)") {
            // Demon Bolt targets "creature or planeswalker". The snapshot path runs
            // hasUnmetTargeting and skips the whole card if the chosen SA has no
            // legal targets — which would also drop the foretell offer. Give the AI
            // a Grizzly Bears so the base SA's targeting is met and the foretell
            // alt-cost branch in addHandAltCostCastActions actually fires.
            val (b, game, _) =
                base.startWithBoard { _, human, ai ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Demon Bolt", human, ZoneType.Hand)
                    base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }

            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!
            val boltIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Hand)
                                .cards
                                .first { it.name == "Demon Bolt" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val foretellOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == boltIid &&
                        it.alternativeGrpId == foretellAbilityGrpId
                }
            assertSoftly(foretellOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == foretellAbilityGrpId }.shouldBeTrue()
            }
        }

        test("foretell card in hand but only one land → no Cast offer with alternativeGrpId=FORETELL row") {
            // Only one Mountain — can't pay foretell action cost {2}. Base Cast at {R}
            // is payable but carries no alternativeGrpId; the foretell offer must be absent.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Demon Bolt", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasActiveForetellOffer =
                actions.actionsList.any { it.alternativeGrpId == foretellAbilityGrpId }
            hasActiveForetellOffer.shouldBeFalse()
        }

        test("foretell card only in graveyard → no Cast offer with alternativeGrpId=FORETELL row") {
            // Foretell hand-action is hand-only. A foretell card in graveyard must not
            // surface a foretell offer.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Demon Bolt", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val boltGrpId = b.cardRepository.findGrpIdByName("Demon Bolt")!!
            val foretellAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(boltGrpId, KeywordAbilityIds.FORETELL)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasOffer =
                actions.actionsList.any { it.alternativeGrpId == foretellAbilityGrpId } ||
                    actions.inactiveActionsList.any { it.alternativeGrpId == foretellAbilityGrpId }
            hasOffer.shouldBeFalse()
        }
    })
