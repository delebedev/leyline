package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.DistributionInteractionResult
import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchDistributionInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:distribution runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun cards(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun cardTargets(cards: List<Card>): List<DistributionTargetRef.Card> = cards.map { DistributionTargetRef.Card(ForgeCardId(it.id)) }

        test("fixed damage distribution publishes the prompt envelope and validates response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val request =
                PromptRequest(
                    promptType = "distribution",
                    message = "Deal damage",
                    options = options.map { it.name },
                    min = options.size,
                    max = options.size,
                    defaultIndex = 0,
                    candidateRefs =
                        options.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                        },
                    route = ResolvedPromptRoute.Distribution(PromptSemantic.DividedAllocationDamage, DistributionRouteKind.Damage),
                    sourceEntityId = source.id,
                )
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            val result = AtomicReference<DistributionInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.distribution.awaitDistribution(request, window, 3_000))
                finished.countDown()
            }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val req = batch.single { it.type == GREMessageType.DistributionReq_695e }
            assertSoftly {
                req.gameStateId shouldBe interaction.gameStateId
                req.allowCancel shouldBe AllowCancel.Abort
                req.allowUndo shouldBe true
                req.prompt.promptId shouldBe PromptIds.DISTRIBUTE_DAMAGE
                req.distributionReq.minAmount shouldBe 5
                req.distributionReq.maxAmount shouldBe 5
                req.distributionReq.minPerTarget shouldBe 1
                req.distributionReq.targetIdsList.size shouldBe 2
                req.distributionReq.validSelectedTargetIdsList shouldContainExactly req.distributionReq.targetIdsList
                coordinator.distribution.submit(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(cardTargets(options)[0] to 2, cardTargets(options)[1] to 3),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().amounts shouldBe mapOf(cardTargets(options)[0] to 2, cardTargets(options)[1] to 3)
                coordinator.distribution.current() shouldBe null
            }
        }

        test("invalid distribution rows do not consume the published window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val request =
                PromptRequest(
                    promptType = "distribution",
                    message = "Put counters",
                    options = options.map { it.name },
                    min = options.size,
                    max = options.size,
                    defaultIndex = 0,
                    route = ResolvedPromptRoute.Distribution(PromptSemantic.DividedAllocationCounters, DistributionRouteKind.Counters),
                    sourceEntityId = source.id,
                )
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Counters,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            Thread { coordinator.distribution.awaitDistribution(request, window, 3_000) }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            assertSoftly {
                coordinator.distribution.submit(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(cardTargets(options)[0] to 1),
                ) shouldBe
                    false
                coordinator.distribution.submit(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(cardTargets(options)[0] to 1, cardTargets(options)[1] to 1),
                ) shouldBe false
                coordinator.distribution.current() shouldBe interaction
                coordinator.distribution.cancel(interaction.interactionId, interaction.gameStateId) shouldBe true
            }
        }

        test("wire distribution rejects an unmapped target instance id") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val request =
                PromptRequest(
                    promptType = "distribution",
                    message = "Deal damage",
                    options = options.map { it.name },
                    min = options.size,
                    max = options.size,
                    defaultIndex = 0,
                    route = ResolvedPromptRoute.Distribution(PromptSemantic.DividedAllocationDamage, DistributionRouteKind.Damage),
                    sourceEntityId = source.id,
                )
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            Thread { coordinator.distribution.awaitDistribution(request, window, 3_000) }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val targetIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasDistributionReq() }
                    .distributionReq
                    .targetIdsList
            assertSoftly {
                coordinator.distribution.submitWire(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(999_999 to 2, targetIds[1] to 3),
                ) shouldBe false
                coordinator.distribution.current() shouldBe interaction
                coordinator.distribution.cancel(interaction.interactionId, interaction.gameStateId) shouldBe true
            }
        }

        test("card and player targets remain distinct when their numeric ids match") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val option = cards(board).first()
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val cardTarget = DistributionTargetRef.Card(ForgeCardId(option.id))
            val playerTarget = DistributionTargetRef.Player(SeatId(option.id))
            cardTarget.id.value shouldBe playerTarget.id.value
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = listOf(cardTarget, playerTarget),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            val result = AtomicReference<DistributionInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.distribution.awaitDistribution(
                        PromptRequest(
                            promptType = "distribution",
                            message = "Deal damage",
                            options = listOf(option.name, "Player"),
                            min = 2,
                            max = 2,
                            defaultIndex = 0,
                            route =
                                ResolvedPromptRoute.Distribution(
                                    PromptSemantic.DividedAllocationDamage,
                                    DistributionRouteKind.Damage,
                                ),
                            sourceEntityId = source.id,
                        ),
                        window,
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val targetIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasDistributionReq() }
                    .distributionReq
                    .targetIdsList
            assertSoftly {
                targetIds.distinct().size shouldBe 2
                coordinator.distribution.submitWire(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(targetIds[0] to 2, targetIds[1] to 3),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().amounts shouldBe mapOf(cardTarget to 2, playerTarget to 3)
            }
        }

        test("distribution window rejects fewer than two targets or a fully constrained total") {
            shouldThrow<IllegalArgumentException> {
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = listOf(DistributionTargetRef.Card(ForgeCardId(10))),
                    amount = 3,
                    minPerTarget = 1,
                    sourceForgeCardId = 20,
                    sourceForgeAbilityId = 30,
                    sourceIsSpell = true,
                )
            }
            shouldThrow<IllegalArgumentException> {
                DistributionWindowValue(
                    kind = DistributionRouteKind.Counters,
                    targets =
                        listOf(
                            DistributionTargetRef.Card(ForgeCardId(10)),
                            DistributionTargetRef.Card(ForgeCardId(11)),
                        ),
                    amount = 2,
                    minPerTarget = 1,
                    sourceForgeCardId = 20,
                    sourceForgeAbilityId = 30,
                    sourceIsSpell = true,
                )
            }
        }
    })
