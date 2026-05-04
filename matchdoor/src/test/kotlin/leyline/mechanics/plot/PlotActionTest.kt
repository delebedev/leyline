package leyline.mechanics.plot

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTestBase
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
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
    "UnnecessaryNotNullOperator",
    // Zone-guard tests assert offer absence via `shouldNot offerAltCost(...)`. The
    // detekt heuristic doesn't recognize custom matchers as equality-shape.
    "WeakAssertionOnly",
)
class PlotActionTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
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
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                    cardRepository = b.cardRepository,
                )

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == brawlerGrpId
                }
            castOffers.shouldNotBeEmpty()
            val plotOffer = castOffers.firstOrNull { it.alternativeGrpId == plottedAbilityGrpId }
            assertSoftly {
                plotOffer should beAltCostOffer(plottedAbilityGrpId)
                plotOffer!!.abilityGrpId shouldBe 0 // alternative rail
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
            assertSoftly {
                plotOffer should beAltCostOffer(plottedAbilityGrpId)
                plotOffer!!.abilityGrpId shouldBe 0 // alternative rail
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
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            actions shouldNot offerAltCost(plottedAbilityGrpId)
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
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            actions shouldNot offerAltCost(plottedAbilityGrpId)
        }
    })
