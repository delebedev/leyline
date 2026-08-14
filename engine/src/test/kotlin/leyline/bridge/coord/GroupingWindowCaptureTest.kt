package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

class GroupingWindowCaptureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:grouping capture
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(cards: List<Card>): PromptRequest =
            PromptRequest(
                promptType = if (cards.size == 1) "confirm" else "choose_cards",
                message = "Arrange cards",
                options = if (cards.size == 1) listOf("Top", "Bottom") else cards.map { it.name },
                min = if (cards.size == 1) 1 else 0,
                max = if (cards.size == 1) 1 else cards.size,
                defaultIndex = 0,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route = ResolvedPromptRoute.Grouping(PromptSemantic.GroupingScry, GroupingContext.Scry_a0f6),
            )

        test("capture accepts only the exact single and multi-card producer envelopes") {
            val board = startPuzzleAtMain1(puzzle)
            val cards =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .toList()
                    .take(2)
            val single = request(cards.take(1))
            val multi = request(cards)

            assertSoftly {
                shouldNotThrowAny { GroupingWindowCapture.initial(single, cards.take(1)) }
                shouldNotThrowAny { GroupingWindowCapture.initial(multi, cards) }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(multi.copy(options = emptyList(), candidateRefs = emptyList(), max = 0), emptyList())
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(single.copy(options = listOf("Top")), cards.take(1))
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(single.copy(min = 0), cards.take(1))
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(single.copy(defaultIndex = 2), cards.take(1))
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(multi.copy(options = listOf("Only one")), cards)
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(multi.copy(min = 1), cards)
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(multi.copy(max = 1), cards)
                }
                shouldThrow<IllegalStateException> {
                    GroupingWindowCapture.initial(multi.copy(defaultIndex = 2), cards)
                }
            }
        }
    })
