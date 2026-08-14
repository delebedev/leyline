package leyline.mechanics.transform

import forge.card.CardStateName
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer

class DfcTransformBridgeTest :
    BoardTest({

        test("activated transform resolves through bridge") {
            val (b, game, _) = startPuzzleAtMain1(DFC_TRANSFORM_PUZZLE)
            val player = game.humanPlayer
            val curtains =
                player
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Concealing Curtains" }

            val abilities =
                curtains.spellAbilities.filter {
                    it.isActivatedAbility && !it.isManaAbility()
                }
            abilities.shouldNotBeEmpty()

            val pending = awaitFreshPending(b, null)
            pending.shouldNotBeNull()

            val submitted =
                b.actionBridge(SeatId(1)).submitTestRuntimeAction(
                    pending.actionId,
                    PlayerAction.ActivateAbility(ForgeCardId(curtains.id), 0),
                )
            submitted.shouldBeTrue()

            val pending2 = awaitFreshPending(b, pending.actionId)
            pending2.shouldNotBeNull()
            b.actionBridge(SeatId(1)).submitTestRuntimeAction(
                pending2.actionId,
                PlayerAction.PassPriority,
            )

            val pending3 = awaitFreshPending(b, pending2.actionId)
            pending3.shouldNotBeNull()

            assertSoftly {
                curtains.isBackSide shouldBe true
                curtains.currentStateName shouldBe CardStateName.Backside
                curtains.name shouldBe "Revealing Eye"
            }
        }
    })

private val DFC_TRANSFORM_PUZZLE =
    """
    [metadata]
    Name:DFC Transform Test
    Goal:Win
    Turns:1
    Difficulty:Tutorial
    Description:Activate Concealing Curtains transform ability

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
    aibattlefield=Runeclaw Bear
    """.trimIndent()
