package leyline.game.bundle

import forge.game.combat.Combat
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.BlockWarningType

class RequestBuilderTest :
    BoardTest({
        test("three-blocker minimum warns for one or two assignments and clears at zero or three") {
            val board =
                startWithBoard { game, human, ai ->
                    val attacker = addCard("Pathrazer of Ulamog", ai, ZoneType.Battlefield)
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Runeclaw Bear", human, ZoneType.Battlefield)
                    game.phaseHandler.combat = Combat(ai).apply { addAttacker(attacker, human) }
                }
            val attacker = board.ai.battlefield.card("Pathrazer of Ulamog")
            val attackerId = board.bridge.instanceId(attacker)
            val blockers =
                listOf("Walking Corpse", "Grizzly Bears", "Runeclaw Bear")
                    .map { board.bridge.instanceId(board.human.battlefield.card(it)) }
            for (count in 0..3) {
                val assignments = blockers.take(count).associateWith { attackerId }
                val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge, assignments)
                assertSoftly {
                    request.hasRestrictions shouldBe true
                    request.blockWarningsCount shouldBe if (count in 1..2) 1 else 0
                    request.blockWarningsList.forEach { warning ->
                        warning.instanceId shouldBe attackerId
                        warning.type shouldBe BlockWarningType.InsufficientBlockers
                        warning.warningPromptId shouldBe 117
                    }
                }
            }
        }

        test("ordinary single block needs no minimum-blocker warning") {
            val board =
                startWithBoard { game, human, ai ->
                    val attacker = addCard("Centaur Courser", ai, ZoneType.Battlefield)
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    game.phaseHandler.combat = Combat(ai).apply { addAttacker(attacker, human) }
                }
            val blockerId = board.bridge.instanceId(board.human.battlefield.card("Walking Corpse"))
            val attackerId = board.bridge.instanceId(board.ai.battlefield.card("Centaur Courser"))
            val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge, mapOf(blockerId to attackerId))
            request.blockWarningsCount shouldBe 0
        }
    })
