package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest

class MatchRevealChoiceInteractionPublicationFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:reveal choice publication failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            aihand=Mountain;Forest
            ailibrary=Grizzly Bears
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.ai
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun entry(board: Board): PromptJournal.RevealEntry {
            val journal = board.bridge.promptBridge(SeatId(1)).journal
            journal.record(PromptSideEffect.RevealStarted(options(board).map { ForgeCardId(it.id) }, SeatId(2)))
            return checkNotNull(journal.activeRevealEntry())
        }

        fun request(
            board: Board,
            sourceId: Int? =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
                    .id,
            max: Int = 1,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card",
                options = options(board).map { it.name },
                min = 1,
                max = max,
                candidateRefs =
                    options(board).mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                sourceEntityId = sourceId,
            )

        test("materialization failure clears only the claimed reveal and retains its diagnostic") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val claimed = entry(board)
            val prior = board.bridge.projectionStateSnapshot()
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.revealChoices.awaitSelection(
                        request(board, sourceId = Int.MAX_VALUE),
                        options(board),
                        claimed,
                        false,
                        3_000,
                    )
                }
            assertSoftly {
                failure.revealChoiceDiagnostic
                    .shouldNotBeNull()
                    .interaction.sourceForgeCardId
                    ?.value shouldBe Int.MAX_VALUE
                failure.pendingRevealChoiceCut.shouldBeNull()
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("invalid producer cardinality clears the claimed reveal before publication") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.revealChoices.awaitSelection(
                    request(board, max = options(board).size + 1),
                    options(board),
                    entry(board),
                    false,
                    3_000,
                )
            }

            assertSoftly {
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
            }
        }

        test("post-install acknowledgement failure retains committed output and projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.revealChoices.afterInstall = { error("reveal acknowledgement unavailable") }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.revealChoices.awaitSelection(
                        request(board),
                        options(board),
                        entry(board),
                        false,
                        3_000,
                    )
                }
            val retained = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                failure.pendingRevealChoiceCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasSelectNReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
            }
        }
    })
