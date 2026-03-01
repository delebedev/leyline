package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.bridge.GameBootstrap

/**
 * Tests for [ScriptedPlayerController] — verifies the scripted AI
 * plays predetermined actions and falls back to passing on exhaustion.
 */
class ScriptedPlayerControllerTest :
    FunSpec({

        var harness: MatchFlowHarness? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("scripted AI plays Forest on turn 1") {
            // AI-first seed: AI goes first, gets priority on turn 1
            val h = MatchFlowHarness(seed = 2L, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Forest"),
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                ),
            )

            // Pass through AI turn + our turn until turn 2
            h.passUntilTurn(2, maxPasses = 30)

            // After AI's turn 1, it should have played a Forest onto the battlefield
            val aiPlayer = h.bridge.getPlayer(2)!!
            val aiBf = aiPlayer.getZone(ZoneType.Battlefield)
            val forests = aiBf.cards.filter { it.name == "Forest" }
            forests.shouldNotBeEmpty()
        }

        test("script exhaustion does not hang") {
            // Empty script — AI should just pass on every decision
            val h = MatchFlowHarness(seed = 2L, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(emptyList())

            // This should not hang — exhausted script falls back to pass
            h.passUntilTurn(2, maxPasses = 30)
            h.isGameOver().shouldBeFalse()
        }

        test("illegal action in script does not hang") {
            // Script tries to play a card that doesn't exist — should warn and pass
            val h = MatchFlowHarness(seed = 2L, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Nonexistent Card"),
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                    ScriptedAction.PassPriority,
                ),
            )

            // Should not hang even with an illegal action
            h.passUntilTurn(2, maxPasses = 30)
            h.isGameOver().shouldBeFalse()
        }
    })
