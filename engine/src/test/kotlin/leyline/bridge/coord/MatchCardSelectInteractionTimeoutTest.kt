package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedCardSelectInteraction
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MatchCardSelectInteractionTimeoutTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select timeout
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest;Plains;Island;Swamp;Wastes;Grizzly Bears;Centaur Courser
            humanlibrary=Grizzly Bears;Centaur Courser
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        data class Case(
            val semantic: PromptSemantic,
            val min: Int,
            val max: Int,
            val libraryCandidates: Boolean = false,
            val sourceRequired: Boolean = true,
            val mappedResolution: Boolean = false,
            val defaultIndex: Int = 0,
        )

        val cases =
            listOf(
                Case(PromptSemantic.SelectNLegendRule, min = 1, max = 1, sourceRequired = false),
                Case(PromptSemantic.SelectNLibraryPutback, min = 2, max = 2),
                Case(PromptSemantic.SelectNResolution, min = 6, max = 6, sourceRequired = false, mappedResolution = true, defaultIndex = 5),
                Case(PromptSemantic.SelectNResolution, min = 0, max = 1, sourceRequired = false, mappedResolution = true),
                Case(PromptSemantic.ManifestDread, min = 1, max = 1, libraryCandidates = true),
                Case(
                    PromptSemantic.SelectNResolution,
                    min = 1,
                    max = 1,
                    sourceRequired = false,
                    mappedResolution = true,
                ),
            )

        fun source(board: Board): Card =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()

        cases.forEach { case ->
            listOf(0L, 25L).forEach { timeoutMs ->
                test("${case.semantic} min ${case.min} fallback at $timeoutMs ms retains legal handles") {
                    val board = startPuzzleAtMain1(puzzle)
                    val coordinator = board.bridge.cutCoordinator
                    coordinator.drain(SeatId(1))
                    val zone = if (case.libraryCandidates) ZoneType.Library else ZoneType.Hand
                    val handles =
                        board.human
                            .getZone(zone)
                            .cards
                            .toList()
                    val request =
                        PromptRequest(
                            promptType = "choose_cards",
                            message = "Choose a card",
                            options = handles.map { it.name },
                            min = case.min,
                            max = case.max,
                            defaultIndex = case.defaultIndex,
                            candidateRefs =
                                handles.mapIndexed { index, card ->
                                    PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, zone.name)
                                },
                            unfilteredRefs =
                                if (case.mappedResolution) {
                                    handles.mapIndexed { index, card ->
                                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, zone.name)
                                    }
                                } else {
                                    emptyList()
                                },
                            route =
                                PromptRouteResolver.resolve(
                                    case.semantic,
                                    resolutionInput =
                                        ResolutionRouteInput(
                                            optionCount = handles.size,
                                            candidateCount = handles.size,
                                            candidateKinds = setOf(PromptCandidateKind.Card),
                                            candidateZones = setOf(zone.name),
                                            abilityShape = ResolutionAbilityShape.Other,
                                            allCandidatesProjectable = true,
                                        ).takeIf { case.mappedResolution },
                                ),
                            sourceEntityId = source(board).id.takeIf { case.sourceRequired },
                        )
                    val signal = PrioritySignal()
                    val publishedAtTimeout = AtomicReference<PublishedCardSelectInteraction>()
                    val timeoutClaims = AtomicInteger()
                    coordinator.prompts.settled.beforeTimeoutClaim = {
                        timeoutClaims.incrementAndGet()
                        publishedAtTimeout.set(checkNotNull(coordinator.cardSelect.current()))
                    }
                    val prompt =
                        InteractivePromptBridge(timeoutMs = timeoutMs, prioritySignal = signal, strict = false).also {
                            it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                        }

                    val result = prompt.requestCardSelect(request, handles)
                    val messages = coordinator.drain(SeatId(1)).flatten()
                    val expected = (listOf(case.defaultIndex) + handles.indices).distinct().take(case.min)

                    assertSoftly {
                        result.optionIndices shouldContainExactly expected
                        result.handles.size shouldBe case.min
                        result.handles.forEachIndexed { index, handle -> (handle === handles[expected[index]]) shouldBe true }
                        coordinator.cardSelect
                            .current()
                            .shouldBeNull()
                    }
                    if (timeoutMs == 0L) {
                        messages.none { it.hasSelectNReq() } shouldBe true
                    } else {
                        val req = messages.single { it.hasSelectNReq() }.selectNReq
                        val published = checkNotNull(publishedAtTimeout.get())
                        assertSoftly {
                            signal.awaitSignal(3_000) shouldBe true
                            timeoutClaims.get() shouldBe 1
                            req.minSel shouldBe case.min
                            req.maxSel shouldBe case.max
                            if (case.semantic == PromptSemantic.ManifestDread) {
                                req.idsList shouldContainExactly req.unfilteredIdsList
                            }
                            coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(req.idsList[1])), published.gameStateId) shouldBe
                                false
                            coordinator.drain(SeatId(1)).flatten().size shouldBe 0
                        }
                    }
                }
            }
        }

        test("no-wait fallback accepts a maximum above the available candidates") {
            val board = startPuzzleAtMain1(puzzle)
            val handles =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .take(2)
            val prompt = InteractivePromptBridge(timeoutMs = 0)
            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose up to three cards",
                    options = handles.map { it.name },
                    min = 0,
                    max = 3,
                    route = PromptRouteResolver.resolve(PromptSemantic.SelectNLibraryPutback),
                )
            val optional = prompt.requestCardSelect(request, handles)
            val mandatory = prompt.requestCardSelect(request.copy(min = 2), handles)
            assertSoftly {
                optional.optionIndices shouldContainExactly emptyList()
                optional.handles shouldContainExactly emptyList()
                mandatory.optionIndices shouldContainExactly listOf(0, 1)
                (mandatory.handles[0] === handles[0]) shouldBe true
                (mandatory.handles[1] === handles[1]) shouldBe true
            }
        }

        test("no-wait fallback rejects impossible bounds and uses offer order for an invalid default") {
            val board = startPuzzleAtMain1(puzzle)
            val handles =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val prompt = InteractivePromptBridge(timeoutMs = 0)
            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose cards",
                    options = handles.map { it.name },
                    min = 2,
                    max = 2,
                    defaultIndex = -1,
                    route = PromptRouteResolver.resolve(PromptSemantic.SelectNLibraryPutback),
                )
            val result = prompt.requestCardSelect(request, handles)
            assertSoftly {
                result.optionIndices shouldContainExactly listOf(0, 1)
                (result.handles[0] === handles[0]) shouldBe true
                (result.handles[1] === handles[1]) shouldBe true
            }
            listOf(request.copy(min = -1), request.copy(min = 3), request.copy(min = 9, max = 9)).forEach { invalid ->
                shouldThrow<IllegalStateException> { prompt.requestCardSelect(invalid, handles) }
            }
        }
    })
