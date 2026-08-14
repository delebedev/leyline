package leyline.bridge.coord

import forge.game.card.Card
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class CardSelectWindowCaptureTest :
    FunSpec({
        tags(UnitTag)

        val cards = listOf(Card(10, null), Card(11, null))
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a legendary permanent to keep",
                options = listOf("First", "Second"),
                min = 1,
                max = 1,
                defaultIndex = 0,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, "Battlefield")
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNLegendRule),
            )

        test("Legend Rule accepts only its exact producer shape") {
            assertSoftly {
                shouldNotThrowAny { CardSelectWindowCapture.initial(request, cards) }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        request.copy(options = listOf("Only"), candidateRefs = request.candidateRefs.take(1)),
                        cards.take(1),
                    )
                }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(min = 0), cards) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(defaultIndex = 1), cards) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(sourceEntityId = 10), cards) }
            }
        }
    })
