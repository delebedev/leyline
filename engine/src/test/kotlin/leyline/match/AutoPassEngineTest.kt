package leyline.match

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import forge.game.GameEndReason
import forge.game.card.Card
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.bridge.coord.PrioritySettingsCommand
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PriorityDecision
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.settingsMessage
import leyline.testkit.submitTestAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private fun openPriorityWindow(bridge: GameBridge): GameActionBridge.PendingAction {
    registerPlaybackForPriorityTest(bridge)
    val actionBridge = bridge.actionBridge(SeatId(1))
    thread(isDaemon = true, name = "auto-pass-test-priority") {
        actionBridge.awaitAction(
            PendingActionState(
                phase = "Main1",
                turn = 1,
                activePlayerId = 1,
                priorityPlayerId = 1,
            ),
        )
    }
    bridge.awaitPriority()
    return checkNotNull(actionBridge.getPending())
}

private fun registerPlaybackForPriorityTest(bridge: GameBridge): GamePlayback =
    bridge.playbackFor(SeatId(1))
        ?: GamePlayback(bridge, seatId = 1, captureLocalActions = false).also {
            checkNotNull(bridge.getGame()).subscribeToEvents(it)
            bridge.registerPlaybackForTest(SeatId(1), it)
        }

private fun closePriorityWindow(
    bridge: GameBridge,
    pending: GameActionBridge.PendingAction,
) {
    bridge.submitTestAction(pending.actionId, PlayerAction.PassPriority)
}

/**
 * Unit tests for [AutoPassEngine] decision logic.
 *
 * Tests [checkHumanActions] directly (internal visibility) using real Game
 * and BundleBuilder from [Board.startWithBoard].
 *
 * Loop-level [autoPassAndAdvance] tests cover game-over detection and
 * Grant-path exits. Skip-path tests (advanceOrWait) are deferred to
 * integration tests with a running game loop.
 */
class AutoPassEngineTest :
    BoardTest({

        timeout = 15.seconds.inWholeMilliseconds

        test("blocking prompt is visible through the coordinator prompt check") {
            lateinit var source: Card
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    source = addCard("Forest", human, ZoneType.Hand)
                }
            registerPlaybackForPriorityTest(bridge)
            val failure = AtomicReference<Throwable>()
            val waiter =
                thread(isDaemon = true, name = "blocking-prompt-visibility") {
                    runCatching {
                        bridge.cutCoordinator.awaitOptional(
                            BlockingInteraction.Optional(ForgeCardId(source.id), true, null, null),
                            3_000,
                            false,
                        )
                    }.onFailure(failure::set)
                }
            val deadline = System.nanoTime() + 3.seconds.inWholeNanoseconds
            var pending = bridge.cutCoordinator.currentBlockingInteraction()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = bridge.cutCoordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(pending) { "Blocking publication failed: ${failure.get()}" }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val handler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx)

            handler.checkPendingPrompt() shouldBe TargetingHandler.PromptResult.SENT_TO_CLIENT

            bridge.cutCoordinator.submitOptionalAnswer(exact.interactionId, exact.gameStateId, true)
            waiter.join(3_000)
            failure.get() shouldBe null
            game.isGameOver shouldBe false
        }

        // --- checkHumanActions: AI turn ---

        test("checkHumanActions — AI turn with pass-only actions returns Skip(OnlyPassActions)") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Skip>().reason shouldBe AutoPassReason.OnlyPassActions
            }
        }

        test("checkHumanActions — AI turn with real action grants priority") {
            val (bridge, game, counter) =
                startWithBoard { _, human, ai ->
                    addCard("Burst Lightning", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", ai, ZoneType.Battlefield)
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
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = true)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>().phase shouldBe "MAIN1"
            }
        }

        test("shouldCheckHumanActions waits for a pending priority window on either turn") {
            val (bridge, game, counter) =
                startWithBoard { _, human, ai ->
                    addCard("Burst Lightning", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", ai, ZoneType.Battlefield)
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
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            assertSoftly {
                engine.shouldCheckHumanActions(isAiTurn = true) shouldBe false
                engine.shouldCheckHumanActions(isAiTurn = false) shouldBe false
            }
        }

        test("checkHumanActions — AI turn with only sorcery-speed hand actions skips") {
            val (bridge, game, counter) =
                startWithBoard { _, human, ai ->
                    addCard("Raging Goblin", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", ai, ZoneType.Battlefield)
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
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            (decision as PriorityDecision.Skip).reason shouldBe AutoPassReason.OnlyPassActions
        }

        // --- checkHumanActions: full control ---

        test("checkHumanActions — full control grants priority even with pass-only actions") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val priorityPolicy = PriorityPolicyRuntime()
            priorityPolicy.submitAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    priorityPolicy = priorityPolicy,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>().phase shouldBe "MAIN1"
            }
        }

        test("checkHumanActions — AI turn full control grants priority even with pass-only actions") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            game.phaseHandler.devModeSet(PhaseType.MAIN1, game.aiPlayer)
            game.phaseHandler.onStackResolved()
            val priorityPolicy = PriorityPolicyRuntime()
            priorityPolicy.submitAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    priorityPolicy = priorityPolicy,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>().phase shouldBe "MAIN1"
            }
        }

        // --- checkHumanActions: client autoPass ---

        test("checkHumanActions — client autoPass + pass-only → Skip(ClientAutoPass)") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val priorityPolicy = PriorityPolicyRuntime()
            priorityPolicy.submit(
                PrioritySettingsCommand(
                    settings = settingsMessage { autoPassOption = AutoPassOption.ResolveAll },
                    humanPlayerId = null,
                    opponentPlayerId = null,
                ),
            )
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    priorityPolicy = priorityPolicy,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Skip>().reason shouldBe AutoPassReason.ClientAutoPass
            }
        }

        test("checkHumanActions — client autoPass + real actions → Grant") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val priorityPolicy = PriorityPolicyRuntime()
            priorityPolicy.submit(
                PrioritySettingsCommand(
                    settings = settingsMessage { autoPassOption = AutoPassOption.ResolveAll },
                    humanPlayerId = null,
                    opponentPlayerId = null,
                ),
            )
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    priorityPolicy = priorityPolicy,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = true)

            assertSoftly {
                decision.shouldBeInstanceOf<PriorityDecision.Grant>().phase shouldBe "MAIN1"
            }
        }

        // --- checkHumanActions: no autoPass ---

        test("checkHumanActions — no autoPass + pass-only → Skip(OnlyPassActions)") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = false)

            decision.shouldBeInstanceOf<PriorityDecision.Skip>().reason shouldBe AutoPassReason.OnlyPassActions
        }

        test("checkHumanActions — real actions → Grant with correct phase") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val decision = engine.checkHumanActions(game, hasLegalAction = true)

            val grant = decision.shouldBeInstanceOf<PriorityDecision.Grant>()
            grant.phase shouldBe "MAIN1"
        }

        // --- checkHumanActions: decision diagnostics ---

        test("checkHumanActions logs structured session decisions") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java) as Logger
            val previousLevel = logger.level
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                engine.checkHumanActions(game, hasLegalAction = false)
                appender.list.single().formattedMessage shouldBe
                    "event=priority_decision source=runtime phase=MAIN1 turn=1 decision=Skip(OnlyPassActions)"
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("AI turn skip logs one runtime decision") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java) as Logger
            val previousLevel = logger.level
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                engine.checkHumanActions(game, hasLegalAction = false)
                appender.list.single().formattedMessage shouldBe
                    "event=priority_decision source=runtime phase=MAIN1 turn=1 decision=Skip(OnlyPassActions)"
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("runtime logs structured priority decisions") {
            val (bridge, game, _) = startWithBoard { _, _, _ -> }
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java) as Logger
            val previousLevel = logger.level
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                bridge.priorityPolicy.recordDecision(game, PriorityDecision.Skip(AutoPassReason.SmartPhaseSkip))
                appender.list.single().formattedMessage shouldBe
                    "event=priority_decision source=runtime phase=MAIN1 turn=1 decision=Skip(SmartPhaseSkip)"
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        // --- autoPassAndAdvance: non-blocking exits ---

        test("autoPassAndAdvance — game over sends sendGameOver and returns") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            game.setGameOver(GameEndReason.AllOpposingTeamsLost)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
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
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val pending = openPriorityWindow(bridge)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                )

            engine.autoPassAndAdvance()

            ops.sendRealGameStateCount shouldBe 1
            ops.sendGameOverCount shouldBe 0
            closePriorityWindow(bridge, pending)
        }

        test("autoPassAndAdvance — full control grants priority on empty board") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val pending = openPriorityWindow(bridge)
            val priorityPolicy = PriorityPolicyRuntime()
            priorityPolicy.submitAutoPassPriority(AutoPassPriority.No_a099)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val engine =
                AutoPassEngine(
                    sink = ops,
                    counters = ops,
                    bundles = ops,
                    pacing = ops,
                    combatHandler = CombatHandler(sink = ops, counters = ops, pacing = ops, ctx = ops.ctx),
                    targetingHandler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx),
                    optionalActionHandler = OptionalActionHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    numericInputHandler = NumericInputHandler(sink = ops, counters = ops, ctx = ops.ctx),
                    ctx = ops.ctx,
                    priorityPolicy = priorityPolicy,
                )

            engine.autoPassAndAdvance()

            ops.sendRealGameStateCount shouldBe 1
            closePriorityWindow(bridge, pending)
        }

        // --- autoPassAndAdvance: combat signal tests ---
        // These use stub CombatHandler (open class) to control the combat signal.

        test("autoPassAndAdvance — combat STOP exits loop immediately") {
            val (bridge, game, counter) = startWithBoard { _, _, _ -> }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
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
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val pending = openPriorityWindow(bridge)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
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
            closePriorityWindow(bridge, pending)
        }

        test("autoPassAndAdvance — SEND_STATE without a pending window emits state only") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            registerPlaybackForPriorityTest(bridge)
            val committed = BundleBuilder(bridge, "auto-pass-test", 1).stateOnlyDiff(game, counter).messages
            bridge.cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), committed)
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)

            val stubCombat =
                object : CombatHandler(
                    sink = ops,
                    counters = ops,
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
