package leyline.mechanics.jumpstart

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldNot
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.offerAltCost

class JumpStartActionTest :
    BoardTest({
        test("Jump-start card in graveyard but no discardable hand card has no alt-cost offer") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Radical Idea", human, ZoneType.Graveyard)
                }
            val radicalIdeaGrpId = b.cardRepository.findGrpIdByName("Radical Idea")!!
            val jumpStartAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(radicalIdeaGrpId, KeywordAbilityIds.JUMP_START)!!

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            actions shouldNot offerAltCost(jumpStartAbilityGrpId)
        }
    })
