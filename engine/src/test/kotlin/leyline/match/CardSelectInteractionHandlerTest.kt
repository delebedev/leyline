package leyline.match

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.cardSelectRuntime
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.effectCostResp
import leyline.testkit.selectNResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CardSelectInteractionHandlerTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select session adapter
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanlibrary=Grizzly Bears;Centaur Courser
            humansideboard=Environmental Sciences
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun request(
            board: Board,
            semantic: PromptSemantic = PromptSemantic.SelectNSacrificeEffect,
            candidates: List<Card> = options(board),
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a permanent",
                options = candidates.map { it.name },
                min = if (semantic == PromptSemantic.LearnLesson) 0 else 1,
                max = 1,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, card.zone.zoneType.name)
                    },
                route =
                    PromptRouteResolver.resolve(
                        semantic,
                        resolutionInput =
                            candidates
                                .takeIf { semantic == PromptSemantic.SelectNResolution }
                                ?.let {
                                    ResolutionRouteInput(
                                        optionCount = candidates.size,
                                        candidateCount = it.size,
                                        candidateKinds = setOf(PromptCandidateKind.Card),
                                        candidateZones = it.mapTo(linkedSetOf()) { card -> card.zone.zoneType.name },
                                        abilityShape = ResolutionAbilityShape.Dig,
                                    )
                                },
                    ),
                sourceEntityId =
                    if (semantic == PromptSemantic.SelectNLegendRule) {
                        null
                    } else {
                        board.human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .single()
                            .id
                    },
            )

        test("EffectCostResp completes the exact coordinator window without a legacy pending prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), handles, 3_000))
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            val exact = checkNotNull(published)
            val selectedId =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList[1]
            var autoPassed = false

            CardSelectInteractionHandler(SessionContext(checkNotNull(board.bridge.getGame()), board.bridge))
                .tryHandleEffectCost(
                    effectCostResp(listOf(selectedId)).toBuilder().setGameStateId(exact.gameStateId).build(),
                ) { autoPassed = true }
                .shouldBeTrue()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldBe listOf(1)
                (result.get().handles.single() === handles[1]) shouldBe true
                autoPassed shouldBe true
                board.bridge
                    .promptBridge(SeatId(1))
                    .getPendingPrompt()
                    .shouldBeNull()
                coordinator.cardSelect.current().shouldBeNull()
            }
        }

        listOf(
            PromptSemantic.SelectNLegendRule,
            PromptSemantic.SelectNLibraryPutback,
            PromptSemantic.ManifestDread,
            PromptSemantic.SelectNResolution,
            PromptSemantic.LearnLesson,
        ).forEach { semantic ->
            test("$semantic rejects EffectCostResp and accepts only SelectNResp") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val handles =
                    if (semantic == PromptSemantic.ManifestDread || semantic == PromptSemantic.SelectNResolution) {
                        board.human
                            .getZone(ZoneType.Library)
                            .cards
                            .toList()
                    } else if (semantic == PromptSemantic.LearnLesson) {
                        board.human
                            .getZone(ZoneType.Sideboard)
                            .cards
                            .toList() + options(board)
                    } else {
                        options(board)
                    }
                val result = AtomicReference<CardSelectInteractionResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(
                            request(board, semantic, handles),
                            handles,
                            3_000,
                        ),
                    )
                    finished.countDown()
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var published = coordinator.cardSelect.current()
                while (published == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    published = coordinator.cardSelect.current()
                }
                val exact = checkNotNull(published)
                val selectedId =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq.idsList[1]
                var autoPassed = false
                val handler = CardSelectInteractionHandler(SessionContext(checkNotNull(board.bridge.getGame()), board.bridge))

                val effectHandled =
                    handler.tryHandleEffectCost(
                        effectCostResp(listOf(selectedId)).toBuilder().setGameStateId(exact.gameStateId).build(),
                    ) { autoPassed = true }

                assertSoftly {
                    effectHandled shouldBe true
                    finished.count shouldBe 1L
                    autoPassed shouldBe false
                    coordinator.cardSelect.current() shouldBe exact
                }

                val selectNHandled =
                    handler.tryHandleSelectN(
                        selectNResp(listOf(selectedId)).toBuilder().setGameStateId(exact.gameStateId).build(),
                    ) { autoPassed = true }

                assertSoftly {
                    selectNHandled shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().optionIndices shouldBe listOf(1)
                    (result.get().handles.single() === handles[1]) shouldBe true
                    autoPassed shouldBe true
                    coordinator.cardSelect.current().shouldBeNull()
                }
            }
        }
    })
