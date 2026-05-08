package leyline.mechanics.impending

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ImpendingActionTest :
    BoardTest({
        test("Forge surfaces Impending as an alternative spell ability") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(4) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Overlord of the Mistmoors", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val overlord = human.getZone(ZoneType.Hand).cards.first { it.name == "Overlord of the Mistmoors" }
            val abilities = getAllCastableAbilities(overlord, human)
            val impendingSa = abilities.firstOrNull { it.alternativeCost == AlternativeCost.Impending }
            val regularSa = abilities.firstOrNull { it.alternativeCost == null }

            assertSoftly {
                impendingSa shouldNotBe null
                impendingSa!!.isImpending shouldBe true
                regularSa shouldNotBe null
            }
        }

        test("ActionMapper offers canonical Impending alt-cost Cast from hand") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(7) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Overlord of the Mistmoors", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val overlord = human.getZone(ZoneType.Hand).cards.first { it.name == "Overlord of the Mistmoors" }
            val overlordIid = b.getOrAllocInstanceId(ForgeCardId(overlord.id)).value
            val overlordGrpId = b.cardRepository.findGrpIdByName("Overlord of the Mistmoors")!!
            val impendingAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(overlordGrpId, KeywordAbilityIds.IMPENDING)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val castOffers = actions.actionsList.filter { it.actionType == ActionType.Cast && it.instanceId == overlordIid }
            val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
            val impendingOffer = castOffers.firstOrNull { it.alternativeGrpId == impendingAbilityGrpId }

            assertSoftly {
                castOffers.shouldNotBeEmpty()
                plainOffers shouldHaveSize 1
                impendingOffer should beAltCostOffer(impendingAbilityGrpId)
                impendingOffer!!.grpId shouldBe overlordGrpId
                impendingOffer.facetId shouldBe overlordIid
                impendingOffer.abilityGrpId shouldBe 0
                impendingOffer should haveManaCost(generic = 2, white = 2)
            }
        }
    })
