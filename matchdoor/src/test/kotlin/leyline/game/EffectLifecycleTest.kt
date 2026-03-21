package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.conformance.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Integration test: verifies the LayeredEffect lifecycle wiring
 * using a real Forge game. Boots a game, builds GSMs, and checks
 * that the effect tracker runs without errors.
 */
class EffectLifecycleTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

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
            val gsm1 = StateMapper.buildFromGame(game, 1, "test", b).gsm
            b.snapshotDiffBaseline(gsm1)

            gsm1 shouldNotBe null
            gsm1.gameStateId shouldBe 1

            // Build a diff — should not crash even with no state changes
            val gsm2 = StateMapper.buildDiffFromGame(game, 2, "test", b).gsm
            gsm2 shouldNotBe null
            gsm2.gameStateId shouldBe 2

            // Verify snapshotBoosts runs without error
            val boosts = b.snapshotBoosts()
            // May or may not have boosts depending on board state — just verify no crash
            boosts shouldNotBe null
        }

        test("prowess cast produces correct LayeredEffect annotation shape") {
            val puzzle = PuzzleSource.loadFromResource("puzzles/prowess-annotation.pzl")
            val b = GameBridge(bridgeTimeoutMs = 10_000, cards = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 10_000
            b.startPuzzle(puzzle)

            val game = b.getGame()!!
            val human = b.getPlayer(SeatId(1))!!

            // Verify setup: Swiftspear on battlefield, Giant Growth in hand
            val swiftspear = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Monastery Swiftspear" }
            val giantGrowth = human.getZone(ZoneType.Hand).cards
                .first { it.name == "Giant Growth" }
            val swiftspearIid = b.getOrAllocInstanceId(ForgeCardId(swiftspear.id)).value

            // Take initial snapshot (gsId=1)
            val gsm1 = StateMapper.buildFromGame(game, 1, "test", b).gsm
            b.snapshotDiffBaseline(gsm1)

            // Cast Giant Growth targeting Swiftspear
            val pending = awaitFreshPending(b, null).shouldNotBeNull()
            b.actionBridge(1).submitAction(pending.actionId, PlayerAction.CastSpell(ForgeCardId(giantGrowth.id)))

            // Engine prompts for target selection (mandatory=false for voluntary casts)
            val targetPrompt = awaitPrompt(b, timeoutMs = 5_000).shouldNotBeNull()
            targetPrompt.request.options.size shouldBe 1 // only Swiftspear
            b.promptBridge(1).submitResponse(targetPrompt.promptId, listOf(0))

            // Pass priority until spell resolves — stop once stack is empty in MAIN1
            // (don't advance to combat or the +X/+X until end of turn effects expire)
            var lastId = pending.actionId
            var passes = 0
            var stackWasNonEmpty = false
            while (passes < 20) {
                val prompt = awaitPrompt(b, timeoutMs = 500)
                if (prompt != null) {
                    b.promptBridge(1).submitResponse(prompt.promptId, listOf(prompt.request.defaultIndex))
                    passes++
                    continue
                }
                val next = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: break
                if (game.stack.size() > 0) stackWasNonEmpty = true
                // Stop once stack empties after having items (spell resolved)
                if (stackWasNonEmpty && game.stack.size() == 0) break
                b.actionBridge(1).submitAction(next.actionId, PlayerAction.PassPriority)
                lastId = next.actionId
                passes++
            }

            // Giant Growth resolved: base 1/2 + prowess +1/+1 + GG +3/+3 = 5/6
            swiftspear.netPower shouldBeGreaterThan 1
            swiftspear.netToughness shouldBeGreaterThan 2

            // Build full GSM to capture all annotations including effects
            val gsm2 = StateMapper.buildFromGame(game, 2, "test", b).gsm

            val allTransient = gsm2.annotationsList
            val allPersistent = gsm2.persistentAnnotationsList

            // --- LayeredEffectCreated transient ---
            val created = allTransient.filter {
                it.typeList.contains(AnnotationType.LayeredEffectCreated)
            }
            created.size shouldBeGreaterThan 0
            val prowessCreated = created.filter { it.affectorId == swiftspearIid }
            prowessCreated.size shouldBeGreaterThan 0

            // --- PowerToughnessModCreated transient companion ---
            val ptmCreated = allTransient.filter {
                it.typeList.contains(AnnotationType.PowerToughnessModCreated)
            }
            ptmCreated.size shouldBeGreaterThan 0

            // --- LayeredEffect persistent (multi-type) ---
            val layeredEffects = allPersistent.filter {
                it.typeList.contains(AnnotationType.LayeredEffect)
            }
            layeredEffects.size shouldBeGreaterThan 0

            // Multi-type array: [ModifiedToughness, ModifiedPower, LayeredEffect]
            val ptEffect = layeredEffects.first { it.affectedIdsList.contains(swiftspearIid) }
            ptEffect.typeList shouldContain AnnotationType.ModifiedToughness
            ptEffect.typeList shouldContain AnnotationType.ModifiedPower
            ptEffect.typeList shouldContain AnnotationType.LayeredEffect

            // affectorId set
            ptEffect.affectorId shouldBe swiftspearIid

            // effect_id detail present
            val effectIdDetail = ptEffect.detailsList.firstOrNull { it.key == "effect_id" }
            effectIdDetail.shouldNotBeNull()
            effectIdDetail.getValueInt32(0) shouldBeGreaterThan 0

            // No spurious LayeredEffectType
            ptEffect.detailsList.none { it.key == "LayeredEffectType" } shouldBe true

            // sourceAbilityGRPID present (prowess keyword mapped)
            val sourceAbility = ptEffect.detailsList.firstOrNull { it.key == "sourceAbilityGRPID" }
            sourceAbility.shouldNotBeNull()
            sourceAbility.getValueInt32(0) shouldBeGreaterThan 0
        }
    })
