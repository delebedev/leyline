package leyline.mechanics.flashback

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
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
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class FlashbackActionTest :
    BoardTest({
        test("Forge surfaces the Flashback alt-cost SA on a graveyard card") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Think Twice" }

            val flashbackSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Flashback }

            flashbackSa shouldNotBe null
        }

        test("offers canonical Cast for flashback card in graveyard") {
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
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )

            val castActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == thinkTwiceIid
                }
            castActions.shouldNotBeEmpty()
            val flashbackOffer = castActions.firstOrNull { it.alternativeGrpId == flashbackAbilityGrpId }
            flashbackOffer should beAltCostOffer(flashbackAbilityGrpId)
            assertSoftly {
                flashbackOffer!!.grpId shouldBe thinkTwiceGrpId
                flashbackOffer.facetId shouldBe thinkTwiceIid
                flashbackOffer.alternativeGrpId shouldBe flashbackAbilityGrpId
                flashbackOffer.abilityGrpId shouldBe 0
                flashbackOffer.alternativeSourceZcid shouldBe thinkTwiceIid
                flashbackOffer should haveManaCost(generic = 2, blue = 1)
            }
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
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )
            actions shouldNot offerAltCost(flashbackAbilityGrpId)
        }
    })
