package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.PlaybackTerminalFailure
import leyline.game.awaitFreshPending
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility

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
            val deferred = board.bridge.cutCoordinator.deferredCast
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
            board.bridge.cutCoordinator.publishSettings(SeatId(1), SettingsMessage.getDefaultInstance())
            deferred.publishOptional(
                claim,
                CastingTimeOptionsReq.getDefaultInstance(),
                List(optionalCount) { it + 1 },
            )
            val published = board.bridge.cutCoordinator.drain(SeatId(1))
            val promptGameStateId =
                published[1]
                    .first { it.hasCastingTimeOptionsReq() }
                    .gameStateId
            val counterBeforeInvalidReceipt = board.counter.snapshot()
            assertSoftly {
                published.size shouldBe 2
                published[0].single().hasSetSettingsResp() shouldBe true
                deferred.publishOptional(
                    DeferredCastReceipt("stale-action", Long.MIN_VALUE),
                    CastingTimeOptionsReq.getDefaultInstance(),
                    List(optionalCount) { it + 1 },
                    preserveHybridStash = true,
                ) shouldBe false
                board.counter.snapshot() shouldBe counterBeforeInvalidReceipt
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .shouldBeEmpty()
            }

            val wrongWindow =
                deferred.admit(
                    DeferredCastResponse(promptGameStateId - 1, 0, null, emptyList()),
                )
            wrongWindow.shouldBeInstanceOf<DeferredCastAdmission.Rejected>()
            deferred.hasPrompt() shouldBe true

            val wrongOption =
                deferred.admit(
                    DeferredCastResponse(promptGameStateId, 999, null, emptyList()),
                )
            wrongOption.shouldBeInstanceOf<DeferredCastAdmission.Rejected>()
            deferred.hasPrompt() shouldBe true

            val accepted =
                deferred.admit(
                    DeferredCastResponse(promptGameStateId, 0, null, emptyList()),
                )
            accepted.shouldBeInstanceOf<DeferredCastAdmission.Optional>()
            deferred.hasPrompt() shouldBe false
            val duplicate =
                deferred.admit(
                    DeferredCastResponse(promptGameStateId, 0, null, emptyList()),
                )
            duplicate.shouldBeInstanceOf<DeferredCastAdmission.Rejected>()
            deferred.hasPrompt() shouldBe false
        }

        test("deferred CastingTimeOptions keeps Player bytes and gives observers projected state only") {
            data class Published(
                val player: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
                val observer: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
            )

            fun publish(withObserver: Boolean): Published {
                val board =
                    startPuzzleAtMain1(
                        """
                        [metadata]
                        Name:deferred viewer projection
                        Goal:Win
                        Turns:1

                        [state]
                        ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=20
                        AILife=20
                        humanhand=Burst Lightning;Mountain
                        humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                        humanlibrary=Mountain
                        aibattlefield=Forest
                        ailibrary=Forest
                        """.trimIndent(),
                    )
                val coordinator = board.bridge.cutCoordinator
                val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
                val cast =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .first { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .first { it.actionType == ActionType.Cast }
                if (withObserver) coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
                val claim =
                    coordinator
                        .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), cast, defer = true)
                        .shouldNotBeNull()
                        .actionClaim
                val optionalCount = checkNotNull(claim.deferredCostPlan?.optional?.entries).size
                coordinator.deferredCast.publishOptional(
                    claim,
                    CastingTimeOptionsReq.getDefaultInstance(),
                    List(optionalCount) { it + 1 },
                )
                val player = coordinator.drain(SeatId(1)).single()
                val observer = if (withObserver) coordinator.drain(SeatId(2)).single() else emptyList()
                val gameStateId = player.single { it.hasCastingTimeOptionsReq() }.gameStateId
                coordinator.deferredCast.cancel(gameStateId) shouldBe true
                return Published(player, observer)
            }

            val playerOnly = publish(withObserver = false)
            val withObserver = publish(withObserver = true)

            assertSoftly {
                withObserver.player.map { it.toByteArray().toList() } shouldBe
                    playerOnly.player.map { it.toByteArray().toList() }
                withObserver.player.any { it.hasCastingTimeOptionsReq() } shouldBe true
                withObserver.observer.size shouldBe 1
                withObserver.observer.single().hasGameStateMessage() shouldBe true
                withObserver.observer.none { it.hasCastingTimeOptionsReq() } shouldBe true
                withObserver.observer
                    .single()
                    .gameStateMessage.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                withObserver.observer
                    .single()
                    .gameStateMessage.gameObjectsList
                    .none { it.visibility == Visibility.Private } shouldBe true
            }
        }

        test("deferred cancellation requires the exact prompt game state") {
            val board =
                startPuzzleAtMain1(
                    puzzle
                        .replace("humanhand=Forest", "humanhand=Burst Lightning")
                        .replace("humanbattlefield=Forest", "humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain")
                        .replace("humanlibrary=Forest", "humanlibrary=Mountain"),
                )
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val deferred = board.bridge.cutCoordinator.deferredCast
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
            deferred.publishOptional(
                claim,
                CastingTimeOptionsReq.getDefaultInstance(),
                List(optionalCount) { it + 1 },
            )
            val promptGameStateId =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasCastingTimeOptionsReq() }
                    .gameStateId

            assertSoftly {
                deferred.cancel(promptGameStateId - 1) shouldBe false
                deferred.hasPrompt() shouldBe true
                board.bridge.actionBridge(SeatId(1)).getPending() shouldBe null

                deferred.cancel(promptGameStateId) shouldBe true
                deferred.hasPrompt() shouldBe false
            }
            val reopenedActionId =
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    ?.actionId
            reopenedActionId shouldBe pending.actionId
        }

        test("deferred prompt install failure leaves no batch projection or prompt") {
            val board =
                startPuzzleAtMain1(
                    puzzle
                        .replace("humanhand=Forest", "humanhand=Burst Lightning")
                        .replace("humanbattlefield=Forest", "humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain")
                        .replace("humanlibrary=Forest", "humanlibrary=Mountain"),
                )
            val coordinator = board.bridge.cutCoordinator
            val pending = checkNotNull(board.bridge.actionBridge(SeatId(1)).getPending())
            val cast =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Cast }
            val claim =
                coordinator
                    .claimPriorityResponse(pending.actionId, checkNotNull(pending.promptGameStateId), cast, defer = true)
                    .shouldNotBeNull()
                    .actionClaim
            val optionalCount = checkNotNull(claim.deferredCostPlan?.optional?.entries).size
            val projection = board.bridge.projectionStateSnapshot()
            coordinator.deferredCast.beforeInstall = { error("deferred prompt install unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.deferredCast.publishOptional(
                    claim,
                    CastingTimeOptionsReq.getDefaultInstance(),
                    List(optionalCount) { it + 1 },
                )
            }

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe projection
                coordinator.deferredCast.hasPrompt() shouldBe false
                coordinator.failure().shouldNotBeNull()
            }
        }
    })
