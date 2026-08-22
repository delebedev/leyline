package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.DistributionInteractionResult
import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
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
                    targetForgeIds = options.map { it.id },
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
                    listOf(options[0].id to 2, options[1].id to 3),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().amounts shouldBe mapOf(options[0].id to 2, options[1].id to 3)
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
                    targetForgeIds = options.map { it.id },
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
                coordinator.distribution.submit(interaction.interactionId, interaction.gameStateId, listOf(options[0].id to 1)) shouldBe
                    false
                coordinator.distribution.submit(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(options[0].id to 1, options[1].id to 1),
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
                    targetForgeIds = options.map { it.id },
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
                coordinator.distribution.submitWire(
                    interaction.interactionId,
                    interaction.gameStateId,
                    listOf(999_999 to 2, options[1].id to 3),
                ) shouldBe false
                coordinator.distribution.current() shouldBe interaction
                coordinator.distribution.cancel(interaction.interactionId, interaction.gameStateId) shouldBe true
            }
        }

        test("distribution window rejects fewer than two targets or a fully constrained total") {
            shouldThrow<IllegalArgumentException> {
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targetForgeIds = listOf(10),
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
                    targetForgeIds = listOf(10, 11),
                    amount = 2,
                    minPerTarget = 1,
                    sourceForgeCardId = 20,
                    sourceForgeAbilityId = 30,
                    sourceIsSpell = true,
                )
            }
        }
    })
