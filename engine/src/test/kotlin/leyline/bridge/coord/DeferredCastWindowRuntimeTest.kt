package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.config.CostChoicePresentation
import leyline.config.EngineSettings
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.CastingTimeOptionsBuilder
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ParameterType

class DeferredCastWindowRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:Alternate additional-cost publication
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Demand Answers;Grizzly Bears
            humanbattlefield=Mountain;Mountain;Ornithopter
            humanlibrary=Island;Island;Island
            ailibrary=Forest;Forest
            """.trimIndent()

        fun Board.claimCast(): MatchActionWindowRuntime.ActionClaim {
            val seat = bridge.seating.humanSeat
            val pending = bridge.actionBridge(seat).getPending().shouldNotBeNull()
            val request =
                bridge.cutCoordinator
                    .feed(seat)
                    .queue
                    .flatMap { it.messages }
                    .last { it.hasActionsAvailableReq() }
            val cast = request.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Cast }
            return bridge.cutCoordinator.actions
                .claimPriority(pending.actionId, request.gameStateId, cast, defer = true)
                .shouldNotBeNull()
                .actionClaim
        }

        fun Board.publish(claim: MatchActionWindowRuntime.ActionClaim) {
            val plan = claim.deferredCostPlan.shouldNotBeNull()
            val choices = plan.alternate.shouldNotBeNull().choices
            val (request, ids) =
                CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                    plan.instanceId,
                    plan.grpId,
                    bridge.seating.humanSeat.value,
                    choices,
                    bridge.engineSettings.costChoicePresentation,
                )
            bridge.cutCoordinator.deferredCast.publishAlternate(claim, request, ids)
        }

        test("text choices publish together and reject an unknown branch without replacing their identity") {
            val board = startPuzzleAtMain1(puzzle, EngineSettings(costChoicePresentation = CostChoicePresentation.ForgeText))
            val claim = board.claimCast()
            board.publish(claim)
            val coordinator = board.bridge.cutCoordinator
            val published = coordinator.feed(board.bridge.seating.humanSeat).queue.flatMap { it.messages }
            val request = published.single { it.hasCastingTimeOptionsReq() }
            val option = request.castingTimeOptionsReq.castingTimeOptionReqList.single()
            val sequence = board.bridge.committedSequence()

            val rejection =
                coordinator.deferredCast.admit(
                    DeferredCastResponse(request.gameStateId, option.ctoId, selectedCtoId = 99, options = emptyList()),
                )

            assertSoftly {
                rejection shouldBe DeferredCastAdmission.Rejected(DeferredCastRejection.WrongOption)
                coordinator.deferredCast.hasPrompt() shouldBe true
                board.bridge.committedSequence() shouldBe sequence
                coordinator.feed(board.bridge.seating.humanSeat).queue.flatMap { it.messages } shouldBe published
                option.selectNReq.idsList shouldBe listOf(1, 2)
                option.selectNReq.prompt.parametersList shouldHaveSize 2
                option.selectNReq.prompt.parametersList
                    .map { it.type } shouldBe
                    listOf(ParameterType.NonLocalizedString, ParameterType.NonLocalizedString)
            }
        }

        test("closed optional-to-mana continuation clears retained cost choices") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Optional life payment closure
                    Goal:Win
                    Turns:1
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Duskwalker
                    humanbattlefield=K'rrik, Son of Yawgmoth;Mountain;Mountain;Mountain
                    humanlibrary=Forest;Forest
                    ailibrary=Forest;Forest
                    """.trimIndent(),
                )
            val claim = board.claimCast()
            val plan = claim.deferredCostPlan.shouldNotBeNull()
            val runtime = board.bridge.cutCoordinator.deferredCast
            val (request, ids) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    plan.instanceId,
                    plan.optional
                        .shouldNotBeNull()
                        .entries,
                    board.bridge.seating.humanSeat.value,
                    plan.optional.baseManaCost,
                )
            runtime.publishOptional(claim, request, ids)
            val published =
                board.bridge.cutCoordinator
                    .feed(board.bridge.seating.humanSeat)
                    .queue
                    .flatMap {
                        it.messages
                    }.last { it.hasCastingTimeOptionsReq() }
            val admission =
                runtime.admit(
                    DeferredCastResponse(published.gameStateId, ids.first(), null, emptyList()),
                ) as DeferredCastAdmission.ManaAfterOptional
            val (manaRequest, manaIds) =
                CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                    plan.instanceId,
                    plan.grpId,
                    board.bridge.seating.humanSeat.value,
                    admission.plan.promptColors,
                    admission.plan.manaCost,
                    alternatives = admission.plan.alternatives,
                )
            runtime.publishHybrid(admission.receipt, manaRequest, manaIds, admission.plan) shouldBe true
            runtime.close(claim.actionId)
            val journal =
                board.bridge
                    .seat(board.bridge.seating.humanSeat)
                    .prompt.journal
            assertSoftly {
                runtime.hasPrompt() shouldBe false
                journal.consumeOptionalCostStash() shouldBe null
                journal.consumeHybridManaStash() shouldBe null
                board.human.life shouldBe 20
                board.human.getCardsIn(forge.game.zone.ZoneType.Battlefield).count { it.isTapped } shouldBe 0
                admission.plan.alternatives shouldBe listOf(ManaColor.Phyrexian_afc9, ManaColor.Phyrexian_afc9)
            }
        }

        test("failed installation publishes no text choices or projection advance") {
            val board = startPuzzleAtMain1(puzzle, EngineSettings(costChoicePresentation = CostChoicePresentation.ForgeText))
            val claim = board.claimCast()
            val coordinator = board.bridge.cutCoordinator
            val feed = coordinator.feed(board.bridge.seating.humanSeat)
            val priorFeed = feed.queue.toList()
            val priorProjection = board.bridge.projectionStateSnapshot()
            coordinator.deferredCast.beforeInstall = { error("Installation unavailable") }

            val failure = shouldThrow<PlaybackTerminalFailure> { board.publish(claim) }

            assertSoftly {
                failure.cause?.message shouldBe "Installation unavailable"
                board.bridge.projectionStateSnapshot() shouldBe priorProjection
                feed.queue.toList() shouldBe priorFeed
                coordinator.deferredCast.hasPrompt() shouldBe false
            }
        }
    })
