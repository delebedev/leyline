package leyline.mechanics.jumpstart

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

class JumpStartActionTest :
    BoardTest({
        test("Forge surfaces the Jump-start SA on a graveyard card with a discardable hand card") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Radical Idea", human, ZoneType.Graveyard)
                    addCard("Coral Merfolk", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Radical Idea" }

            val jumpStartSa = getAllCastableAbilities(card, human).firstOrNull { it.isJumpstart }

            jumpStartSa shouldNotBe null
        }

        test("offers CastThroughAbility for Jump-start card in graveyard") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Radical Idea", human, ZoneType.Graveyard)
                    addCard("Coral Merfolk", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val radicalIdea = human.getZone(ZoneType.Graveyard).cards.firstOrNull { it.name == "Radical Idea" }
            radicalIdea shouldNotBe null
            val radicalIdeaIid = b.getOrAllocInstanceId(ForgeCardId(radicalIdea!!.id)).value
            val radicalIdeaGrpId = b.cardRepository.findGrpIdByName("Radical Idea")!!
            val jumpStartAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(radicalIdeaGrpId, KeywordAbilityIds.JUMP_START)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val castActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == radicalIdeaIid
                }
            castActions.shouldNotBeEmpty()
            val jumpStartOffer = castActions.firstOrNull { it.alternativeGrpId == jumpStartAbilityGrpId }

            jumpStartOffer should beAltCostOffer(jumpStartAbilityGrpId)
            assertSoftly {
                jumpStartOffer!!.grpId shouldBe radicalIdeaGrpId
                jumpStartOffer.facetId shouldBe radicalIdeaIid
                jumpStartOffer.alternativeGrpId shouldBe jumpStartAbilityGrpId
                jumpStartOffer.abilityGrpId shouldBe jumpStartAbilityGrpId
                jumpStartOffer.alternativeSourceZcid shouldBe radicalIdeaIid
                jumpStartOffer should haveManaCost(generic = 1, blue = 1)
            }
        }

        test("Jump-start card in graveyard but no discardable hand card has no alt-cost offer") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Radical Idea", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val radicalIdeaGrpId = b.cardRepository.findGrpIdByName("Radical Idea")!!
            val jumpStartAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(radicalIdeaGrpId, KeywordAbilityIds.JUMP_START)!!
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Radical Idea" }

            val jumpStartSa = getAllCastableAbilities(card, human).firstOrNull { it.isJumpstart }
            jumpStartSa shouldBe null

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            actions shouldNot offerAltCost(jumpStartAbilityGrpId)
        }
    })
