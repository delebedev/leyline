package leyline.mechanics.harmonize

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
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

class HarmonizeActionTest :
    BoardTest({
        test("Forge surfaces Harmonize as a graveyard alternative spell ability") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Winternight Stories", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Winternight Stories" }

            val harmonizeAbilities = getAllCastableAbilities(card, human).filter { it.alternativeCost?.name == "Harmonize" }

            harmonizeAbilities.shouldNotBeEmpty()
        }

        test("ActionMapper offers canonical Harmonize alt-cost Cast from graveyard") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Winternight Stories", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Winternight Stories" }
            val sourceIid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val sourceGrpId = b.cardRepository.findGrpIdByName("Winternight Stories")!!
            val harmonizeAbilityGrpId = b.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.HARMONIZE)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val harmonizeOffer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == sourceIid &&
                        it.alternativeGrpId == harmonizeAbilityGrpId
                }

            assertSoftly {
                harmonizeOffer should beAltCostOffer(harmonizeAbilityGrpId)
                harmonizeOffer!!.grpId shouldBe sourceGrpId
                harmonizeOffer.facetId shouldBe sourceIid
                harmonizeOffer.abilityGrpId shouldBe 0
                harmonizeOffer.alternativeSourceZcid shouldBe sourceIid
                harmonizeOffer should haveManaCost(generic = 4, blue = 1)
            }
        }
    })
