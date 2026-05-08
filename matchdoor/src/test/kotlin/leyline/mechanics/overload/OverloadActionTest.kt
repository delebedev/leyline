package leyline.mechanics.overload

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

class OverloadActionTest :
    BoardTest({
        test("Forge surfaces Overload as targetless alternative spell ability") {
            val (_, game, _) =
                startWithBoard { _, human, ai ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mizzium Mortars", human, ZoneType.Hand)
                    addCard("Serra Angel", ai, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val mortars = human.getZone(ZoneType.Hand).cards.first { it.name == "Mizzium Mortars" }
            val abilities = getAllCastableAbilities(mortars, human)
            val overloadSa = abilities.firstOrNull { it.alternativeCost == AlternativeCost.Overload }
            val regularSa = abilities.firstOrNull { it.alternativeCost == null }

            assertSoftly {
                overloadSa shouldNotBe null
                overloadSa!!.usesTargeting() shouldBe false
                regularSa shouldNotBe null
                regularSa!!.usesTargeting() shouldBe true
            }
        }

        test("ActionMapper offers canonical Overload alt-cost Cast from hand") {
            val (b, game, _) =
                startWithBoard { _, human, ai ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Mizzium Mortars", human, ZoneType.Hand)
                    addCard("Serra Angel", ai, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val mortars = human.getZone(ZoneType.Hand).cards.first { it.name == "Mizzium Mortars" }
            val mortarsIid = b.getOrAllocInstanceId(ForgeCardId(mortars.id)).value
            val mortarsGrpId = b.cardRepository.findGrpIdByName("Mizzium Mortars")!!
            val overloadAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(mortarsGrpId, KeywordAbilityIds.OVERLOAD)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val castOffers = actions.actionsList.filter { it.actionType == ActionType.Cast && it.instanceId == mortarsIid }
            val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
            val overloadOffer = castOffers.firstOrNull { it.alternativeGrpId == overloadAbilityGrpId }

            assertSoftly {
                castOffers.shouldNotBeEmpty()
                plainOffers shouldHaveSize 1
                overloadOffer should beAltCostOffer(overloadAbilityGrpId)
                overloadOffer!!.grpId shouldBe mortarsGrpId
                overloadOffer.facetId shouldBe mortarsIid
                overloadOffer.abilityGrpId shouldBe 0
                overloadOffer should haveManaCost(generic = 3, red = 3)
            }
        }
    })
