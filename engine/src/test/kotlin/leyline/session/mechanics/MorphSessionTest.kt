package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import leyline.testkit.shouldHaveNoLostPlays
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

private fun morphPuzzle(card: String) =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=$card
    humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Forest;Forest
    humanlibrary=Mountain;Mountain;Mountain;Mountain
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

private fun MatchFlowHarness.offers(): List<Action> =
    allMessages
        .lastOrNull { it.hasActionsAvailableReq() }
        ?.actionsAvailableReq
        ?.actionsList
        .orEmpty()

class MorphSessionTest :
    SessionTest({
        for ((card, power) in listOf("Blistering Firecat" to 7, "Den Protector" to 3)) {
            session(
                "$card cast face down offers Special_TurnFaceUp and flips in place",
                puzzle = morphPuzzle(card),
                turns = 6,
                offerDiff = true,
            ) {
                val iid = bridge.instanceId(human.getZone(ZoneType.Hand).cards.single())
                val faceDownCast =
                    offers().firstOrNull {
                        it.actionType == ActionType.Cast && it.instanceId == iid && it.alternativeGrpId != 0
                    }
                withClue("face-down cast offer") { faceDownCast.shouldNotBeNull() }
                castSpellByName(card, alternativeGrpId = faceDownCast!!.alternativeGrpId) shouldBe true
                passPriority()

                val faceDown =
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.isFaceDown }
                val faceDownIid = bridge.instanceId(faceDown)
                bridge.offerDiffDiagnostics.snapshot().shouldHaveNoLostPlays()
                val turnUp =
                    offers().firstOrNull {
                        it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == faceDownIid
                    }
                // protocol: Special_TurnFaceUp.offer
                withClue("Special_TurnFaceUp offer on the face-down permanent") { turnUp.shouldNotBeNull() }
                assertSoftly {
                    turnUp!!.alternativeGrpId shouldNotBe 0
                    turnUp.manaCostCount shouldBeGreaterThan 0
                }

                send(
                    submitWithGsId(
                        performAction {
                            actionType = ActionType.SpecialTurnFaceUp_add3
                            instanceId = faceDownIid
                        },
                    ),
                )
                drainSink()

                // protocol: Special_TurnFaceUp.keeps-instance
                val flipped =
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first { it.id == faceDown.id }
                assertSoftly {
                    bridge.instanceId(flipped) shouldBe faceDownIid
                    flipped.isFaceDown shouldBe false
                    flipped.name shouldBe card
                    flipped.netPower shouldBe power
                }
            }
        }
    })
