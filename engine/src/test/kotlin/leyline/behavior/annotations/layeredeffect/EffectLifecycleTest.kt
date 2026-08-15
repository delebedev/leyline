package leyline.behavior.annotations.layeredeffect

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.event.FrameEventLog
import leyline.game.generator.PuzzleSource
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import java.util.concurrent.TimeUnit
import leyline.testkit.StateMapperShell as StateMapper

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
            val b = GameBridge(cardRepository = TestCardRegistry.repo, bridgeTimeoutMs = 5000)
            bridge = b
            b.priorityWaitMs = 5000

            b.start(
                seed = 42,
                deckList =
                    """
                    20 Forest
                    20 Grizzly Bears
                    20 Giant Growth
                    """.trimIndent(),
            )

            val game = b.getGame()!!

            // Build full state — exercises effect-fact materialization + effectAnnotations
            val snapEff1 = GsmSnapshot.capture(game, b, "test", 1)
            val gsm1 =
                StateMapper
                    .buildFromSnapshot(
                        snapEff1,
                        1,
                        "test",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gsm1 shouldNotBe null
            gsm1.gameStateId shouldBe 1

            // Build a diff — should not crash even with no state changes
            val snapEff2 = GsmSnapshot.capture(game, b, "test", 2)
            val gsm2 =
                StateMapper
                    .buildDiff(
                        snapEff1,
                        snapEff2,
                        FrameEventLog.EMPTY,
                        2,
                        "test",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            gsm2 shouldNotBe null
            gsm2.gameStateId shouldBe 2
        }

        test("prowess cast produces correct LayeredEffect annotation shape") {
            val puzzle = PuzzleSource.loadFromResource("puzzles/prowess-annotation.pzl")
            val b = GameBridge(bridgeTimeoutMs = 10_000, cardRepository = TestCardRegistry.repo)
            bridge = b
            b.priorityWaitMs = 10_000
            b.startPuzzle(puzzle)
            TestCardRegistry.registerPuzzleCards(b.getGame()!!)

            val game = b.getGame()!!
            val human = b.getPlayer(SeatId(1))!!

            // Verify setup: Swiftspear on battlefield, Giant Growth in hand
            val swiftspear =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Monastery Swiftspear" }
            val giantGrowth =
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Giant Growth" }
            val swiftspearIid = b.getOrAllocInstanceId(ForgeCardId(swiftspear.id)).value

            // Take initial snapshot (gsId=1)
            val snapEff2 = GsmSnapshot.capture(game, b, "test", 1)
            StateMapper.buildFromSnapshot(
                snapEff2,
                1,
                "test",
                b,
                effectFacts = b.materializeEffectProjectionFacts(),
                abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
            )

            // Cast Giant Growth targeting Swiftspear
            val pending = awaitFreshPending(b, null).shouldNotBeNull()
            b.actionBridge(SeatId(1)).submitTestRuntimeAction(pending.actionId, PlayerAction.CastSpell(ForgeCardId(giantGrowth.id)))

            // Engine prompts for target selection (mandatory=false for voluntary casts)
            val targetingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var targeting =
                b.cutCoordinator.targeting
                    .current()
            while (targeting == null && System.nanoTime() < targetingDeadline) {
                Thread.onSpinWait()
                targeting =
                    b.cutCoordinator.targeting
                        .current()
            }
            val initial = targeting.shouldNotBeNull()
            val targetInstanceId =
                b.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
                    .targetsList
                    .single()
                    .targetInstanceId
            val tap =
                b.cutCoordinator.targeting
                    .submitToggle(
                        initial.interactionId,
                        initial.gameStateId,
                        initial.targetIndex,
                        listOf(leyline.bridge.handoff.TargetToggleValue(targetInstanceId, selected = true)),
                    ).shouldNotBeNull()
            b.cutCoordinator.drain(SeatId(1))
            b.cutCoordinator.targeting
                .acknowledgeDelivery(tap.interactionId, checkNotNull(tap.deliveryToken)) shouldBe true
            val latest =
                b.cutCoordinator.targeting
                    .current()
                    .shouldNotBeNull()
            val done =
                b.cutCoordinator.targeting
                    .submitTargets(latest.interactionId, latest.gameStateId)
                    .shouldNotBeNull()
            b.cutCoordinator.drain(SeatId(1))
            b.cutCoordinator.targeting
                .acknowledgeDelivery(done.interactionId, checkNotNull(done.deliveryToken)) shouldBe true

            // Pass priority until spell resolves — stop once stack is empty in MAIN1
            // (don't advance to combat or the +X/+X until end of turn effects expire)
            var lastId = pending.actionId
            var passes = 0
            var stackWasNonEmpty = false
            while (passes < 20) {
                val next = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: break
                if (game.stack.size() > 0) stackWasNonEmpty = true
                // Stop once stack empties after having items (spell resolved)
                if (stackWasNonEmpty && game.stack.size() == 0) break
                b.actionBridge(SeatId(1)).submitTestRuntimeAction(next.actionId, PlayerAction.PassPriority)
                lastId = next.actionId
                passes++
            }

            // Giant Growth resolved: base 1/2 + prowess +1/+1 + GG +3/+3 = 5/6
            swiftspear.netPower shouldBeGreaterThan 1
            swiftspear.netToughness shouldBeGreaterThan 2

            val playbackGsms =
                b.playback
                    ?.drainQueue()
                    .orEmpty()
                    .flatten()
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

            // Build full GSM as a final state sanity check; event-backed effect
            // annotations may already have been emitted by playback split frames.
            val snapEff3 = GsmSnapshot.capture(game, b, "test", 2)
            val gsm2 =
                StateMapper
                    .buildFromSnapshot(
                        snapEff3,
                        2,
                        "test",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            val allTransient = playbackGsms.flatMap { it.annotationsList } + gsm2.annotationsList
            val allPersistent = playbackGsms.flatMap { it.persistentAnnotationsList } + gsm2.persistentAnnotationsList

            // --- LayeredEffectCreated transient ---
            val created =
                allTransient.filter {
                    it.typeList.contains(AnnotationType.LayeredEffectCreated)
                }
            created.size shouldBeGreaterThan 0
            val prowessCreated = created.filter { it.affectorId == swiftspearIid }
            prowessCreated.size shouldBeGreaterThan 0

            // --- PowerToughnessModCreated transient companion ---
            val ptmCreated =
                allTransient.filter {
                    it.typeList.contains(AnnotationType.PowerToughnessModCreated)
                }
            ptmCreated.size shouldBeGreaterThan 0

            // --- LayeredEffect persistent (multi-type) ---
            val layeredEffects =
                allPersistent.filter {
                    it.typeList.contains(AnnotationType.LayeredEffect)
                }
            layeredEffects.size shouldBeGreaterThan 0

            // Multi-type array: [ModifiedToughness, ModifiedPower, LayeredEffect]
            val ptEffect = layeredEffects.first { it.affectedIdsList.contains(swiftspearIid) }
            assertSoftly {
                ptEffect.typeList shouldContain AnnotationType.ModifiedToughness
                ptEffect.typeList shouldContain AnnotationType.ModifiedPower
                ptEffect.typeList shouldContain AnnotationType.LayeredEffect
            }

            // affectorId set
            ptEffect.affectorId shouldBe swiftspearIid

            val effectIdDetail = ptEffect.detailsList.firstOrNull { it.key == "effect_id" }
            val sourceAbility = ptEffect.detailsList.firstOrNull { it.key == "sourceAbilityGRPID" }
            assertSoftly {
                effectIdDetail.shouldNotBeNull()
                effectIdDetail.getValueInt32(0) shouldBeGreaterThan 0
                ptEffect.detailsList.map { it.key } shouldNotContain "LayeredEffectType"
                sourceAbility.shouldNotBeNull()
                sourceAbility.getValueInt32(0) shouldBeGreaterThan 0
            }
        }
    })
