package leyline.mechanics.flashback

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class FlashbackActionTest :
    BoardTest({
        test("offers Cast for flashback card in graveyard") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val thinkTwice = human.getZone(ZoneType.Graveyard).cards.firstOrNull { it.name == "Think Twice" }
            thinkTwice shouldNotBe null
            val thinkTwiceIid = b.getOrAllocInstanceId(ForgeCardId(thinkTwice!!.id)).value
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            val castActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == thinkTwiceIid
                }
            castActions.shouldNotBeEmpty()
            val flashbackOffer = castActions.firstOrNull { it.abilityGrpId == flashbackAbilityGrpId }
            flashbackOffer shouldNotBe null
            assertSoftly {
                flashbackOffer!!.grpId shouldBe thinkTwiceGrpId
                flashbackOffer.facetId shouldBe thinkTwiceIid
                flashbackOffer should haveManaCost(generic = 2, blue = 1)
            }
        }
    })
