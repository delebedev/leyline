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
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ObjectMapper
import leyline.game.snapshot.SnapshotCapture
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Plot hand-activation path (Hand → Exile face-up plotted).
 *
 * Plot is a hand-keyword whose SA is registered by Forge as a KeywordInstance
 * (`isPlotting=true`), NOT an `AlternativeCost`. Bridge surfaces it via the
 * same hand-cast rail as Warp/Sneak; the offer carries `alternativeGrpId =
 * PLOTTED keyword ability grpId` and the SA's mana cost.
 *
 * Cast-from-exile leg uses `AlternativeCost.Plotted` from a card already
 * sitting in Exile with the Plotted designation. That state can't be
 * programmatically synthesized without driving the action, so this file
 * focuses on the hand activation. The exile-cast leg is empirically verified
 * via `puzzles/plot-railway-brawler.pzl` + bot-match.
 *
 * Card: Railway Brawler (Sorcery 4G, Plot {3}{G}).
 */
@Suppress(
    "MissingAssertSoftly",
    "UnnecessaryNotNullOperator",
    // Zone-guard tests assert the absence of an offer — boolean predicates on
    // the action list are the native idiom (no equality-shape to assert).
    "WeakAssertionOnly",
)
class PlotTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Forge surfaces the Plot hand SA on a hand card (isPlotting=true)") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Railway Brawler" }

            val plotSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.isPlotting }
            plotSa shouldNotBe null
        }

        test("ActionMapper offers Cast for plot card in hand when mana available (alternativeGrpId=PLOTTED row)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                    grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cardRepository) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId) },
                    cardRepository = b.cardRepository,
                )

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == brawlerGrpId
                }
            castOffers.shouldNotBeEmpty()
            val plotOffer = castOffers.firstOrNull { it.alternativeGrpId == plottedAbilityGrpId }
            assertSoftly(plotOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == plottedAbilityGrpId }.shouldBeTrue()
            }
        }

        test("ActionMapper.buildFromSnapshot offers Cast for plot card in hand (snapshot path parity)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Railway Brawler", human, ZoneType.Hand)
                }

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!
            val brawlerIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Hand)
                                .cards
                                .first { it.name == "Railway Brawler" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val plotOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == brawlerIid &&
                        it.alternativeGrpId == plottedAbilityGrpId
                }
            assertSoftly(plotOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == plottedAbilityGrpId }.shouldBeTrue()
            }
        }

        test("plot card in hand but insufficient mana → no Cast offer with alternativeGrpId=PLOTTED row") {
            // Plot {3}{G} unpayable with 2 Forests. The non-alt-cost base Cast at 4G
            // is also unpayable. Both must be absent from active offers.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                    grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cardRepository) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId) },
                )

            val hasActivePlotOffer =
                actions.actionsList.any { it.alternativeGrpId == plottedAbilityGrpId }
            hasActivePlotOffer.shouldBeFalse()
        }

        test("plot card only in graveyard → no Cast offer with alternativeGrpId=PLOTTED row") {
            // Plot is hand-only. A plot card in graveyard must not surface a plot offer.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Railway Brawler", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                    grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cardRepository) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId) },
                )

            val hasOffer =
                actions.actionsList.any { it.alternativeGrpId == plottedAbilityGrpId } ||
                    actions.inactiveActionsList.any { it.alternativeGrpId == plottedAbilityGrpId }
            hasOffer.shouldBeFalse()
        }
    })
