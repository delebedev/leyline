package leyline.session.combat

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import kotlin.time.Duration.Companion.seconds

private val HUMAN_COMBAT_PRIORITY_HANDOFF_PUZZLE =
    """
    [metadata]
    Name:Human Combat Priority Handoff
    Goal:Win
    Turns:3
    Difficulty:Easy
    Description:Human attacks with no instants in hand; verifies DECLARE_BLOCKERS hand-off doesn't stall

    [state]
    ActivePlayer=Human
    ActivePhase=COMBAT_DECLARE_ATTACKERS
    HumanLife=20
    AILife=20

    humanhand=
    humanbattlefield=Raging Goblin;Mountain
    humanlibrary=Mountain;Mountain;Mountain
    aibattlefield=
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

/**
 * Regression test for the priority-handoff hang during combat resolution on
 * the human's turn.
 *
 * Puzzle: human has Raging Goblin untapped on a turn-3-style board with no
 * cards in hand (guarantees pass-only priority after attackers go in). AI has
 * no creatures (zero blockers, clean path to damage).
 */
// Session-tier: exercises the full MatchSession + runtime continuation.
@Suppress("TierPlacementCheck")
class HumanCombatPriorityHandoffTest :
    SessionTest({

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        session(
            "human attacks, no blockers — DECLARE_BLOCKERS pass-only priority doesn't hang bridge",
            puzzle = HUMAN_COMBAT_PRIORITY_HANDOFF_PUZZLE,
            timeout = 3.seconds,
        ) {
            passUntil(maxPasses = 5) {
                bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.DECLARE_ATTACKERS
            }.shouldBeTrue()

            val attackerIid =
                humanBattlefieldCreatures()
                    .single { it.second == "Raging Goblin" }
                    .first
            declareAttackers(listOf(attackerIid))
            // Configured combat stops remain visible even when Pass is the only action.
            passPriority()
            passPriority()

            ai.life shouldBe 19
        }
    })
