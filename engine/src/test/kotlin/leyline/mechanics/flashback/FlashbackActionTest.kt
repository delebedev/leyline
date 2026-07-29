package leyline.mechanics.flashback

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.buildPriorityActionsForTest
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class FlashbackActionTest :
    BoardTest({
        test("unpayable flashback card in graveyard is inactive") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val thinkTwiceIid = human.graveyard.iid("Think Twice")
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!

            val actions =
                buildPriorityActionsForTest(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )

            val activeCastActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == thinkTwiceIid
                }
            activeCastActions.shouldBeEmpty()
            val flashbackOffer =
                actions.inactiveActionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == thinkTwiceIid &&
                        it.alternativeGrpId == flashbackAbilityGrpId
                }
            flashbackOffer should beAltCostOffer(flashbackAbilityGrpId)
        }

        test("flashback card only in hand has no graveyard alt-cost offer") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Think Twice", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Think Twice" }

            val handFlashbackSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Flashback }
            handFlashbackSa shouldBe null

            val actions =
                buildPriorityActionsForTest(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )
            actions shouldNot offerAltCost(flashbackAbilityGrpId)
        }
    })
