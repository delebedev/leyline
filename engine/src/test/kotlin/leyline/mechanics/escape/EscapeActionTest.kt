package leyline.mechanics.escape

import forge.game.spellability.AlternativeCost
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
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTestBase
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Escape graveyard-cast-with-alternate-cost path.
 *
 * Escape is a graveyard alt-cost keyword: cast a card from your graveyard for
 * mana plus an additional cost (exile N other cards from your graveyard).
 * Forge registers the SA via `K:Escape:<cost>|Type:Card|N:<count>` with
 * `setAlternativeCost(AlternativeCost.Escape)`.
 *
 * Bridge wiring (mirrors Disturb minus DFC concerns):
 *  - `KEYWORD_BASE_IDS["ESCAPE"] = 199` resolves Glimpse's per-card escape
 *    ability id (136624 in Arena DB).
 *  - `addZoneCastActionsFromSnap` adds Escape to `isMinimalEmit` set — Cast
 *    offer carries no grpId/facetId, only abilityGrpId+alternativeGrpId set
 *    to the per-card escape row.
 *  - `ActionPerformer.resolveAltCostAbilityIndex` matches
 *    `info.baseId == KEYWORD_BASE_IDS["ESCAPE"]` → `AlternativeCost.Escape`.
 *  - The N-other-cards additional cost is paid via Forge's
 *    `Cost.payAdditionalCosts` pipeline; existing prompt machinery surfaces
 *    the selection.
 *
 * Card: Glimpse of Freedom (Instant {U}, "Draw a card.", Escape {2}{U}+exile-5).
 */
@Suppress("UnnecessaryNotNullOperator")
class EscapeActionTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Forge surfaces the Escape alt-cost SA on a graveyard card with ≥5 other cards in GY") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Glimpse of Freedom" }

            val escapeSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Escape }
            escapeSa shouldNotBe null
        }

        test("ActionMapper.buildFromSnapshot offers Cast for escape card in graveyard with ≥5 others + mana") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                }

            val glimpseGrpId = b.cardRepository.findGrpIdByName("Glimpse of Freedom")!!
            val escapeAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(glimpseGrpId, KeywordAbilityIds.ESCAPE)!!
            val glimpseIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Graveyard)
                                .cards
                                .first { it.name == "Glimpse of Freedom" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val castOffers =
                fromSnap.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == glimpseIid
                }
            castOffers.shouldNotBeEmpty()
            val escapeOffer = castOffers.firstOrNull { it.abilityGrpId == escapeAbilityGrpId }
            assertSoftly {
                escapeOffer should beAltCostOffer(escapeAbilityGrpId)
                // Minimal-emit shape (Escape-specific): NO grpId, NO facetId on the offer.
                escapeOffer!!.grpId shouldBe 0
                escapeOffer.facetId shouldBe 0
                escapeOffer.alternativeGrpId shouldBe escapeAbilityGrpId
            }
        }

        test("escape card only in hand → no graveyard-cast offer (zone guard)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Glimpse of Freedom", human, ZoneType.Hand)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
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
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
                    base.addCard("Plains", human, ZoneType.Graveyard)
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
