package leyline.mechanics.cleave

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

class CleaveActionTest :
    BoardTest({
        test("Forge surfaces Cleave as a non-basic hand spell ability") {
            val (_, game, _) =
                startWithBoard { _, human, ai ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Path of Peril", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val path = human.getZone(ZoneType.Hand).cards.first { it.name == "Path of Peril" }

            val cleaveSa =
                getAllCastableAbilities(path, human)
                    .firstOrNull { it.hasParam("PrecostDesc") && it.getParam("PrecostDesc") == "Cleave" }

            cleaveSa shouldNotBe null
        }

        test("ActionMapper offers canonical Cleave alt-cost Cast from hand") {
            val (b, game, _) =
                startWithBoard { _, human, ai ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Path of Peril", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val path = human.getZone(ZoneType.Hand).cards.first { it.name == "Path of Peril" }
            val pathIid = b.getOrAllocInstanceId(ForgeCardId(path.id)).value
            val pathGrpId = b.cardRepository.findGrpIdByName("Path of Peril")!!
            val cleaveAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(pathGrpId, KeywordAbilityIds.CLEAVE)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == pathIid
                }
            castOffers.shouldNotBeEmpty()

            val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
            val cleaveOffer = castOffers.firstOrNull { it.alternativeGrpId == cleaveAbilityGrpId }
            assertSoftly {
                plainOffers shouldHaveSize 1
                cleaveOffer should beAltCostOffer(cleaveAbilityGrpId)
                cleaveOffer!!.grpId shouldBe pathGrpId
                cleaveOffer.facetId shouldBe pathIid
                cleaveOffer.abilityGrpId shouldBe 0
                cleaveOffer should haveManaCost(generic = 4, white = 1, black = 1)
            }
        }
    })
