package leyline.match

import forge.game.GameEndReason
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.BoardTag
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.PriorityDecision
import leyline.match.AutoPassEngine
import leyline.match.CombatHandler
import leyline.match.NumericInputHandler
import leyline.match.OptionalActionHandler
import leyline.match.TargetingHandler
import leyline.testkit.BoardTestBase
import leyline.testkit.aiPlayer
import leyline.testkit.settingsMessage
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [AutoPassEngine] decision logic.
 *
 * Tests [checkHumanActions] directly (internal visibility) using real Game
 * and BundleBuilder from [BoardTestBase.startWithBoard].
 *
 * Loop-level [autoPassAndAdvance] tests cover game-over detection and
 * Grant-path exits. Skip-path tests (advanceOrWait) are deferred to
 * integration tests with a running game loop.
 */
class AutoPassEngineTest :
    FunSpec({

        tags(BoardTag)

        timeout = 15.seconds.inWholeMilliseconds

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- checkHumanActions: AI turn ---

        test("checkHumanActions — AI turn with pass-only actions returns Skip(OnlyPassActions)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = true)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Skip>()
                (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
            }
        }

        test("checkHumanActions — AI turn with real action grants priority") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, ai ->
                    base.addCard("Burst Lightning", human, ZoneType.Hand)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Raging Goblin", ai, ZoneType.Battlefield)
                }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = true)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>()
                (decision as PriorityDecision.Grant).phase shouldBe "MAIN1"
            }
        }

        test("shouldCheckHumanActions — AI turn waits for pending human priority") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, ai ->
                    base.addCard("Burst Lightning", human, ZoneType.Hand)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Raging Goblin", ai, ZoneType.Battlefield)
                }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.shouldCheckHumanActions(isAiTurn = true) shouldBe false
        }

        test("checkHumanActions — AI turn with only sorcery-speed hand actions skips") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, ai ->
                    base.addCard("Raging Goblin", human, ZoneType.Hand)
                    base.addCard("Mountain", human, ZoneType.Hand)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Raging Goblin", ai, ZoneType.Battlefield)
                }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = true)

            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
        }

        // --- checkHumanActions: full control ---

        test("checkHumanActions — full control grants priority even with pass-only actions") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.updateAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    autoPassState = autoPassState,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>()
                (decision as PriorityDecision.Grant).phase shouldBe "MAIN1"
            }
        }

        test("checkHumanActions — AI turn full control grants priority even with pass-only actions") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val autoPassState = ClientAutoPassState()
            autoPassState.updateAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    autoPassState = autoPassState,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = true)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>()
                (decision as PriorityDecision.Grant).phase shouldBe "MAIN1"
            }
        }

        // --- checkHumanActions: client autoPass ---

        test("checkHumanActions — client autoPass + pass-only → Skip(ClientAutoPass)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    autoPassState = autoPassState,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Skip>()
                (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.ClientAutoPass
            }
        }

        test("checkHumanActions — client autoPass + real actions → Grant") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val autoPassState = ClientAutoPassState()
            autoPassState.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    autoPassState = autoPassState,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>()
                (decision as PriorityDecision.Grant).phase shouldBe "MAIN1"
            }
        }

        // --- checkHumanActions: no autoPass ---

        test("checkHumanActions — no autoPass + pass-only → Skip(OnlyPassActions)") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Skip>()
            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
        }

        test("checkHumanActions — real actions → Grant with correct phase") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, isAiTurn = false)

            decision.shouldBeInstanceOf<PriorityDecision.Grant>()
            val grant = decision as PriorityDecision.Grant
            grant.phase shouldBe "MAIN1"
        }

        // --- checkHumanActions: decision log ---

        test("checkHumanActions records decisions in decisionLog") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.decisionLog().size shouldBe 0
            engine.checkHumanActions(game, isAiTurn = false)
            engine.decisionLog().size shouldBe 1
            engine
                .decisionLog()
                .first()
                .decision
                .shouldBeInstanceOf<PriorityDecision.Skip>()
        }

        test("AI turn skip does not record in decisionLog") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.checkHumanActions(game, isAiTurn = true)
            engine.decisionLog().size shouldBe 0
        }

        // --- autoPassAndAdvance: non-blocking exits ---

        test("autoPassAndAdvance — game over sends sendGameOver and returns") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            game.setGameOver(GameEndReason.AllOpposingTeamsLost)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.autoPassAndAdvance()

            assertSoftly {
                ops.sendGameOverCount shouldBe 1
                ops.sendRealGameStateCount shouldBe 0
            }
        }

        test("autoPassAndAdvance — Grant from real actions sends state and exits") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.autoPassAndAdvance()

            ops.sendRealGameStateCount shouldBe 1
            ops.sendGameOverCount shouldBe 0
        }

        test("autoPassAndAdvance — full control grants priority on empty board") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val autoPassState = ClientAutoPassState()
            autoPassState.updateAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, bundles = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    autoPassState = autoPassState,
                )

            engine.autoPassAndAdvance()

            ops.sendRealGameStateCount shouldBe 1
        }

        // --- autoPassAndAdvance: combat signal tests ---
        // These use stub CombatHandler (open class) to control the combat signal.

        test("autoPassAndAdvance — combat STOP exits loop immediately") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    ctx = ops.ctx,
                ) {
                    override fun checkCombatPhase(
                        phase: forge.game.phase.PhaseType?,
                        isHumanTurn: Boolean,
                        isAiTurn: Boolean,
                    ): Signal = Signal.STOP
                }

            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = stubCombat,
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )
            engine.autoPassAndAdvance()

            ops.sendRealGameStateCount shouldBe 0
            ops.sendGameOverCount shouldBe 0
        }

        test("autoPassAndAdvance — SEND_STATE with real actions exits via Grant") {
            val (bridge, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    ctx = ops.ctx,
                ) {
                    override fun checkCombatPhase(
                        phase: forge.game.phase.PhaseType?,
                        isHumanTurn: Boolean,
                        isAiTurn: Boolean,
                    ): Signal = Signal.SEND_STATE
                }

            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = stubCombat,
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )
            engine.autoPassAndAdvance()

            // Human turn + real actions → sendRealGameState from SEND_STATE path
            ops.sendRealGameStateCount shouldBe 1
            ops.sendGameOverCount shouldBe 0
        }

        test("autoPassAndAdvance — SEND_STATE with pass-only actions emits state-only bundle") {
            val (bridge, game, counter) = base.startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    ctx = ops.ctx,
                ) {
                    override fun checkCombatPhase(
                        phase: forge.game.phase.PhaseType?,
                        isHumanTurn: Boolean,
                        isAiTurn: Boolean,
                    ): Signal = Signal.SEND_STATE
                }

            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = stubCombat,
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )
            engine.autoPassAndAdvance()

            val bundle = ops.sentGRE.single()
            assertSoftly {
                ops.sendRealGameStateCount shouldBe 0
                ops.sendGameOverCount shouldBe 0
                ops.sentGRE.size shouldBe 1
                // state-only bundle now ships content GSM + trailing echo GSM.
                bundle.size shouldBe 2
                bundle[0].hasGameStateMessage() shouldBe true
                bundle[0].hasActionsAvailableReq() shouldBe false
                bundle[1].hasGameStateMessage() shouldBe true
                bundle[1].hasActionsAvailableReq() shouldBe false
                // Trailing echo invariant: matching updateType, no content fields.
                val content = bundle[0].gameStateMessage
                val echo = bundle[1].gameStateMessage
                echo.update shouldBe content.update
                echo.annotationsCount shouldBe 0
                echo.persistentAnnotationsCount shouldBe 0
                echo.zonesCount shouldBe 0
                echo.gameObjectsCount shouldBe 0
            }
        }
    })
