package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
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
            humanhand=Mountain;Forest
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
        )

        val cases =
            listOf(
                Case(PromptSemantic.SelectNLegendRule, min = 1, max = 1, sourceRequired = false),
                Case(PromptSemantic.SelectNLibraryPutback, min = 2, max = 2),
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
            test("${case.semantic} timeout returns its exact default handle and rejects a late response") {
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
                        defaultIndex = 0,
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
                coordinator.prompts.settled.beforeTimeoutClaim = {
                    publishedAtTimeout.set(checkNotNull(coordinator.cardSelect.current()))
                }
                val prompt =
                    InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal, strict = false).also {
                        it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                    }

                val result = prompt.requestCardSelect(request, handles)
                val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSelectNReq() }
                val req = requestMessage.selectNReq
                val published = checkNotNull(publishedAtTimeout.get())

                assertSoftly {
                    result.optionIndices shouldContainExactly listOf(0)
                    (result.handles.single() === handles[0]) shouldBe true
                    signal.awaitSignal(3_000) shouldBe true
                    coordinator.cardSelect
                        .current()
                        .shouldBeNull()
                    req.minSel shouldBe case.min
                    req.maxSel shouldBe case.max
                    if (case.semantic == PromptSemantic.ManifestDread) {
                        req.idsList shouldContainExactly req.unfilteredIdsList
                    }
                    coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(req.idsList[1])), published.gameStateId) shouldBe false
                }
            }
        }
    })
