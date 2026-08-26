package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.PlaybackTerminalFailure
import leyline.game.awaitFreshPending
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class MatchActionClaimTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:action claim lifecycle
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Forest
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        test("deferred claim blocks engine progress and FloatMana resolves the pass offer") {
            val board = startPuzzleAtMain1(puzzle, EngineSettings(timer = true))
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            board.bridge.cutCoordinator.drain(SeatId(1))
            val floatMana = Action.newBuilder().setActionType(ActionType.FloatMana).build()
            val phase = board.game.phaseHandler.phase

            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), floatMana, defer = true)
                    .shouldNotBeNull()

            assertSoftly {
                board.bridge.actionBridge(SeatId(1)).getPending() shouldBe null
                board.game.phaseHandler.phase shouldBe phase
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .filter { it.hasTimerStateMessage() }
                    .flatMap { it.timerStateMessage.timersList }
                    .count { !it.running } shouldBe 1
            }
            board.bridge.cutCoordinator.reopenActionClaim(claim.actionClaim) shouldBe true
            val reopened =
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldNotBeNull()
            reopened.actionId shouldBe pending.actionId
            board.bridge.cutCoordinator
                .drain(SeatId(1))
                .flatten()
                .count { it.hasTimerStateMessage() } shouldBe 0
            val retry =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(reopened.promptGameStateId), floatMana, defer = false)
                    .shouldNotBeNull()
            board.bridge.cutCoordinator.completeActionClaim(retry.actionClaim) shouldBe true
            awaitFreshPending(board.bridge, pending.actionId, timeoutMs = 3_000).shouldNotBeNull()
        }

        test("claimed action failure wakes the engine with the terminal cause") {
            val board = startPuzzleAtMain1(puzzle)
            val actionBridge = board.bridge.actionBridge(SeatId(1))
            val pending = checkNotNull(actionBridge.getPending())
            val pass =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), pass, defer = true)
                    .shouldNotBeNull()
            board.bridge.cutCoordinator.drain(SeatId(1))
            val cause = IllegalStateException("post-claim failure")

            val terminal = shouldThrow<PlaybackTerminalFailure> { board.bridge.cutCoordinator.failActionClaim(claim.actionClaim, cause) }

            assertSoftly {
                terminal.cause shouldBe cause
                actionBridge.getPending() shouldBe null
                board.bridge.cutCoordinator.failure() shouldBe terminal
            }
        }

        test("reopen publication failure terminalizes the claimed window") {
            val board = startPuzzleAtMain1(puzzle)
            val actionBridge = board.bridge.actionBridge(SeatId(1))
            val pending = checkNotNull(actionBridge.getPending())
            val pass =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), pass, defer = true)
                    .shouldNotBeNull()
            board.bridge.cutCoordinator.drain(SeatId(1))
            val cause = IllegalStateException("reopen delivery unavailable")
            board.bridge.cutCoordinator.beforeActionEnqueue = { throw cause }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    board.bridge.cutCoordinator.reopenActionClaim(claim.actionClaim)
                }
            board.bridge.cutCoordinator.beforeActionEnqueue = null

            assertSoftly {
                failure.cause shouldBeSameInstanceAs cause
                board.bridge.cutCoordinator.failure() shouldBeSameInstanceAs failure
                actionBridge.getPending() shouldBe null
                board.bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe false
            }
        }

        test("failure after claim completion terminalizes once without masking the first cause") {
            val board = startPuzzleAtMain1(puzzle)
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val pass =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Pass }
            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), pass, defer = false)
                    .shouldNotBeNull()
            board.bridge.cutCoordinator.completeActionClaim(claim.actionClaim) shouldBe true
            val firstCause = IllegalStateException("failure after completion")
            val first = shouldThrow<PlaybackTerminalFailure> { board.bridge.cutCoordinator.failActionClaim(claim.actionClaim, firstCause) }
            val second =
                shouldThrow<PlaybackTerminalFailure> {
                    board.bridge.cutCoordinator.failActionClaim(claim.actionClaim, IllegalArgumentException("later failure"))
                }

            assertSoftly {
                first.cause shouldBeSameInstanceAs firstCause
                second shouldBeSameInstanceAs first
                board.bridge.cutCoordinator.failure() shouldBeSameInstanceAs first
            }
        }

        test("deferred admission owns exact correlation and retires duplicate responses") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:deferred admission
                    Goal:Win
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Burst Lightning
                    humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                    humanlibrary=Mountain
                    aibattlefield=Forest
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val cast =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Cast }
            val claim =
                board.bridge.cutCoordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), cast, defer = true)
                    .shouldNotBeNull()
                    .actionClaim
            val optionalCount = checkNotNull(claim.deferredCostPlan?.optional?.entries).size
            board.bridge.cutCoordinator.publishDeferredOptional(claim, 700, List(optionalCount) { it + 1 })

            val wrongWindow =
                board.bridge.cutCoordinator.admitDeferredCastResponse(
                    MatchActionWindowRuntime.DeferredCastResponse(699, 0, null, emptyList()),
                )
            wrongWindow.shouldBeInstanceOf<MatchActionWindowRuntime.DeferredCastAdmission.Rejected>()
            board.bridge.cutCoordinator.hasDeferredCastPrompt() shouldBe true

            val wrongOption =
                board.bridge.cutCoordinator.admitDeferredCastResponse(
                    MatchActionWindowRuntime.DeferredCastResponse(700, 999, null, emptyList()),
                )
            wrongOption.shouldBeInstanceOf<MatchActionWindowRuntime.DeferredCastAdmission.Rejected>()
            board.bridge.cutCoordinator.hasDeferredCastPrompt() shouldBe true

            val accepted =
                board.bridge.cutCoordinator.admitDeferredCastResponse(
                    MatchActionWindowRuntime.DeferredCastResponse(700, 0, null, emptyList()),
                )
            accepted.shouldBeInstanceOf<MatchActionWindowRuntime.DeferredCastAdmission.Optional>()
            board.bridge.cutCoordinator.hasDeferredCastPrompt() shouldBe false
            val duplicate =
                board.bridge.cutCoordinator.admitDeferredCastResponse(
                    MatchActionWindowRuntime.DeferredCastResponse(700, 0, null, emptyList()),
                )
            duplicate.shouldBeInstanceOf<MatchActionWindowRuntime.DeferredCastAdmission.Rejected>()
            board.bridge.cutCoordinator.hasDeferredCastPrompt() shouldBe false
        }
    })
