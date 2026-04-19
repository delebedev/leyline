package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the priority-handoff hang during combat resolution on
 * the human's turn.
 *
 * Before the fix, [leyline.match.AutoPassEngine.autoPassAndAdvance] at the
 * SEND_STATE human-turn + pass-only branch emitted a state-only diff and
 * `return`ed without auto-passing the engine's pending
 * [leyline.bridge.forge.PlayerController.chooseSpellAbilityToPlay] action. The
 * client received a state-only diff (non-actionable) and never responded, so
 * [leyline.bridge.GameActionBridge.awaitAction] hung until [bridgeTimeoutMs].
 *
 * Puzzle: human has Raging Goblin untapped on a turn-3-style board with no
 * cards in hand (guarantees pass-only priority after attackers go in). AI has
 * no creatures (zero blockers, clean path to damage).
 *
 * With the bug, declareAttackers() returns but the engine sits at
 * COMBAT_DECLARE_BLOCKERS pending. The 3s test timeout catches the hang
 * before the bridge timeout recovers. With the fix, combat resolves in
 * <100ms and AI life drops to 19.
 */
// Session-tier: exercises the full MatchSession + AutoPassEngine loop.
@Suppress("TierPlacementCheck")
class HumanCombatPriorityHandoffTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("human attacks, no blockers — DECLARE_BLOCKERS pass-only priority doesn't hang bridge")
            .config(timeout = 3.seconds) {
                val puzzleText = """
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

                val h = MatchFlowHarness(validating = false)
                harness = h
                h.connectAndKeepPuzzleText(puzzleText)

                // Puzzle starts at MAIN1 on human's turn; advance to combat so
                // the harness sees a live DeclareAttackersReq.
                if (h.phase() == "MAIN1") h.passPriority()

                val attackerIid = h.humanBattlefieldCreatures()
                    .single { it.second == "Raging Goblin" }.first
                h.declareAttackers(listOf(attackerIid))

                // If the bug were present, the engine would block in awaitAction at
                // COMBAT_DECLARE_BLOCKERS after AI declared blocks; damage never applies
                // within the 3s test timeout. With the fix, the auto-pass loop falls
                // through to advanceOrWait and Raging Goblin (1/1) resolves unblocked.
                val aiPlayer = h.bridge.getPlayer(SeatId(2))!!
                aiPlayer.life shouldBe 19
            }
    })
