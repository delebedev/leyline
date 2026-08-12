package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

class StateFrameInputTest :
    FunSpec({
        tags(UnitTag)

        test("zero-Forge state-frame input replays prompt facts without consumption") {
            val choice = PromptSideEffect.ChoiceResult(ForgeCardId(1), SeatId(1), choiceValue = 9)
            val facts =
                PromptProjectionFacts(
                    choiceResults =
                        listOf(
                            PromptProjectionFacts.ChoiceResultFact(
                                SeatId(1),
                                PromptJournal.ChoiceResultEntry(version = 1, result = choice),
                            ),
                        ),
                )
            val input =
                StateFrameInput(
                    gameStateId = 1,
                    snapshot = GsmSnapshot.forTest(matchId = "state-frame"),
                    previousSnapshot = null,
                    events = FrameEventLog.EMPTY,
                    promptFacts = facts,
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = 1,
                    revealForSeat = null,
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())

            val first =
                StateMapper
                    .buildDiff(input, "state-frame", bridge)
                    .finalizeAnnotations()
            val second =
                StateMapper
                    .buildDiff(input, "state-frame", bridge)
                    .finalizeAnnotations()

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                first.mutations.promptFactConsumption.choiceResults
                    .map { it.result } shouldContainExactly listOf(choice)
                second.mutations.promptFactConsumption shouldBe first.mutations.promptFactConsumption
            }
        }
    })
