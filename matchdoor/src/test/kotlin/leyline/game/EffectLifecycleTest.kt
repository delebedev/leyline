package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag

/**
 * Integration test: verifies the LayeredEffect lifecycle wiring
 * using a real Forge game. Boots a game, builds GSMs, and checks
 * that the effect tracker runs without errors.
 */
class EffectLifecycleTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        test("effect tracker initializes and runs without errors during GSM build") {
            val b = GameBridge(bridgeTimeoutMs = 5000)
            bridge = b
            b.priorityWaitMs = 5000

            b.start(
                seed = 42,
                deckList = """
                    20 Forest
                    20 Grizzly Bears
                    20 Giant Growth
                """.trimIndent(),
            )

            val game = b.getGame()!!

            // Build full state — exercises snapshotBoosts + diffBoosts + effectAnnotations
            val gsm1 = StateMapper.buildFromGame(game, 1, "test", b)
            b.snapshotState(gsm1)

            gsm1 shouldNotBe null
            gsm1.gameStateId shouldBe 1

            // Build a diff — should not crash even with no state changes
            val gsm2 = StateMapper.buildDiffFromGame(game, 2, "test", b)
            gsm2 shouldNotBe null
            gsm2.gameStateId shouldBe 2

            // Verify snapshotBoosts runs without error
            val boosts = b.snapshotBoosts()
            // May or may not have boosts depending on board state — just verify no crash
            boosts shouldNotBe null
        }
    })
