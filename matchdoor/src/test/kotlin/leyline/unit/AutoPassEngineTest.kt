package leyline.unit

import forge.game.GameEndReason
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.ConformanceTag
import leyline.bridge.AutoPassReason
import leyline.bridge.ClientAutoPassState
import leyline.bridge.PriorityDecision
import leyline.conformance.ConformanceTestBase
import leyline.conformance.settingsMessage
import leyline.game.GameBridge
import leyline.match.AutoPassEngine
import leyline.match.CombatHandler
import leyline.match.MatchEventType
import leyline.match.TargetingHandler
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [AutoPassEngine] decision logic.
 *
 * Tests [checkHumanActions] directly (internal visibility) using real Game
 * and BundleBuilder from [ConformanceTestBase.startWithBoard].
 *
 * Loop-level [autoPassAndAdvance] tests cover game-over detection and
 * Grant-path exits. Skip-path tests (advanceOrWait) are deferred to
 * integration tests with a running game loop.
 */
class AutoPassEngineTest :
    FunSpec({

        tags(ConformanceTag)

        timeout = 15.seconds.inWholeMilliseconds

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- checkHumanActions: AI turn ---

        test("checkHumanActions — AI turn always returns Skip(OnlyPassActions)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            val decision = engine.checkHumanActions(game, isAiTurn = true)

            decision.shouldBeInstanceOf<PriorityDecision.Skip>()
            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
        }

        // --- checkHumanActions: full control ---

        test("checkHumanActions — full control grants priority even with pass-only actions") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.updateAutoPassPriority(AutoPassPriority.No_a099)
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops), autoPassState)

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Grant>()
            ops.hasTrace(MatchEventType.SEND_STATE) shouldBe true
            ops.hasTraceContaining("fullControl") shouldBe true
        }

        // --- checkHumanActions: client autoPass ---

        test("checkHumanActions — client autoPass + pass-only → Skip(ClientAutoPass)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops), autoPassState)

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Skip>()
            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.ClientAutoPass
            ops.hasTrace(MatchEventType.AUTO_PASS) shouldBe true
        }

        test("checkHumanActions — client autoPass + real actions → Grant") {
            val (bridge, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val autoPassState = ClientAutoPassState()
            autoPassState.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops), autoPassState)

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Grant>()
        }

        // --- checkHumanActions: no autoPass ---

        test("checkHumanActions — no autoPass + pass-only → Skip(OnlyPassActions)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Skip>()
            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
        }

        test("checkHumanActions — real actions → Grant with correct phase") {
            val (bridge, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Grant>()
            val grant = decision as PriorityDecision.Grant
            grant.phase shouldBe "MAIN1"
            ops.hasTrace(MatchEventType.SEND_STATE) shouldBe true
        }

        // --- checkHumanActions: decision log ---

        test("checkHumanActions records decisions in decisionLog") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            engine.decisionLog().size shouldBe 0
            engine.checkHumanActions(game, isAiTurn = false)
            engine.decisionLog().size shouldBe 1
            engine.decisionLog().first().decision.shouldBeInstanceOf<PriorityDecision.Skip>()
        }

        test("AI turn skip does not record in decisionLog") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            engine.checkHumanActions(game, isAiTurn = true)
            engine.decisionLog().size shouldBe 0
        }

        // --- autoPassAndAdvance: non-blocking exits ---

        test("autoPassAndAdvance — game over sends sendGameOver and returns") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            game.setGameOver(GameEndReason.AllOpposingTeamsLost)
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            engine.autoPassAndAdvance(bridge)

            ops.sendGameOverCount shouldBe 1
            ops.sendRealGameStateCount shouldBe 0
            ops.hasTrace(MatchEventType.GAME_OVER) shouldBe true
        }

        test("autoPassAndAdvance — Grant from real actions sends state and exits") {
            val (bridge, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            engine.autoPassAndAdvance(bridge)

            ops.sendRealGameStateCount shouldBe 1
            ops.sendGameOverCount shouldBe 0
        }

        test("autoPassAndAdvance — full control grants priority on empty board") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.updateAutoPassPriority(AutoPassPriority.No_a099)
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops), autoPassState)

            engine.autoPassAndAdvance(bridge)

            ops.sendRealGameStateCount shouldBe 1
            ops.hasTraceContaining("fullControl") shouldBe true
        }

        test("autoPassAndAdvance — null game returns immediately") {
            val bridge = GameBridge()
            val ops = RecordingSessionOps()
            val engine = AutoPassEngine(ops, CombatHandler(ops), TargetingHandler(ops))

            engine.autoPassAndAdvance(bridge)

            ops.sendRealGameStateCount shouldBe 0
            ops.sendGameOverCount shouldBe 0
            ops.tracedEvents.size shouldBe 0
        }

        // --- autoPassAndAdvance: combat signal tests ---
        // These use stub CombatHandler (open class) to control the combat signal.

        test("autoPassAndAdvance — combat STOP exits loop immediately") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)

            val stubCombat = object : CombatHandler(ops) {
                override fun checkCombatPhase(
                    bridge: GameBridge,
                    game: forge.game.Game,
                    phase: forge.game.phase.PhaseType?,
                    isHumanTurn: Boolean,
                    isAiTurn: Boolean,
                ): Signal = Signal.STOP
            }

            val engine = AutoPassEngine(ops, stubCombat, TargetingHandler(ops))
            engine.autoPassAndAdvance(bridge)

            ops.sendRealGameStateCount shouldBe 0
            ops.sendGameOverCount shouldBe 0
        }

        test("autoPassAndAdvance — SEND_STATE with real actions exits via Grant") {
            val (bridge, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val ops = RecordingSessionOps(bridge = bridge, counter = counter)

            val stubCombat = object : CombatHandler(ops) {
                override fun checkCombatPhase(
                    bridge: GameBridge,
                    game: forge.game.Game,
                    phase: forge.game.phase.PhaseType?,
                    isHumanTurn: Boolean,
                    isAiTurn: Boolean,
                ): Signal = Signal.SEND_STATE
            }

            val engine = AutoPassEngine(ops, stubCombat, TargetingHandler(ops))
            engine.autoPassAndAdvance(bridge)

            // Human turn + real actions → sendRealGameState from SEND_STATE path
            ops.sendRealGameStateCount shouldBe 1
            ops.sendGameOverCount shouldBe 0
        }
    })
