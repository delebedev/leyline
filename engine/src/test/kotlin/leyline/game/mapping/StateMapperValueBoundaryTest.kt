package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardProtoBuilder
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateMapperValueBoundaryTest :
    FunSpec({
        tags(UnitTag)

        test("explicit values replay two frames without mutating discarded prior state") {
            val cards = InMemoryCardRepository()
            val environment =
                StateProjectionEnvironment(
                    cardProto = CardProtoBuilder(cards),
                    matchConfig = MatchProjectionConfig(isBrawlOrCommander = false),
                    cardReferences = ProjectionCardReferences(cards),
                )
            val cardId = ForgeCardId(201)
            val initial = ProjectionState.initial()
            val initialBefore = initial.copy()
            val firstSnapshot = valueSnapshot(cardId, gameStateId = 1)
            val firstInput =
                valueFrame(
                    snapshot = firstSnapshot,
                    previous = null,
                    exhaustionFacts =
                        AbilityExhaustionFacts(
                            listOf(AbilityExhaustionFacts.Row(cardId, 7001, usesRemaining = 0, uniqueAbilityId = 50)),
                        ),
                )

            val first = StateProjectionCompiler.compileOneViewer(environment, firstInput, initial)
            val firstRetry = StateProjectionCompiler.compileOneViewer(environment, firstInput, initial)
            val firstNext = checkNotNull(first.transition).nextState

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe firstRetry.gsm.toByteArray().toList()
                first.transition shouldBe firstRetry.transition
                initial shouldBe initialBefore
                initial.identities.forgeIdToInstanceId shouldBe emptyMap()
                firstNext.identities.forgeIdToInstanceId shouldBe mapOf(cardId to InstanceId(100))
                first.gsm.persistentAnnotationsList
                    .filter { AnnotationType.AbilityExhausted in it.typeList }
                    .map { it.id } shouldContainExactly listOf(1)
            }

            val firstNextBefore = firstNext.copy()
            val secondInput =
                valueFrame(
                    snapshot = valueSnapshot(cardId, gameStateId = 2),
                    previous = first.projectionSnapshot,
                    exhaustionFacts = AbilityExhaustionFacts(),
                )
            val second = StateProjectionCompiler.compileOneViewer(environment, secondInput, firstNext)
            val secondRetry = StateProjectionCompiler.compileOneViewer(environment, secondInput, firstNext)

            assertSoftly {
                second.gsm.toByteArray().toList() shouldBe secondRetry.gsm.toByteArray().toList()
                second.transition shouldBe secondRetry.transition
                firstNext shouldBe firstNextBefore
                second.gsm.persistentAnnotationsList shouldBe emptyList()
                second.gsm.diffDeletedPersistentAnnotationIdsList shouldContainExactly listOf(1)
                second.transition.nextState.identities shouldBe firstNext.identities
            }
        }
    })

private fun valueSnapshot(
    cardId: ForgeCardId,
    gameStateId: Int,
): GsmSnapshot {
    val card =
        CardSnapshot(
            forgeCardId = cardId,
            name = "Value Card",
            grpId = 9201,
            owner = SeatId(1),
            controller = SeatId(1),
            isOnBattlefield = true,
            netPower = 1,
            netToughness = 1,
        )
    return GsmSnapshot.forTest(
        matchId = "value-boundary",
        gameStateId = gameStateId,
        objects = mapOf(cardId to card),
        zones =
            mapOf(
                ZoneIds.BATTLEFIELD to
                    ZoneSnapshot(
                        id = ZoneIds.BATTLEFIELD,
                        type = ZoneType.Battlefield,
                        owner = null,
                        visibility = Visibility.Public,
                        contents = listOf(cardId),
                    ),
            ),
    )
}

private fun valueFrame(
    snapshot: GsmSnapshot,
    previous: GsmSnapshot?,
    exhaustionFacts: AbilityExhaustionFacts,
): StateFrameInput =
    StateFrameInput(
        gameStateId = snapshot.gameStateId,
        snapshot = snapshot,
        previousSnapshot = previous,
        events = FrameEventLog.EMPTY,
        promptFacts = PromptProjectionFacts(),
        updateType = GameStateUpdate.SendAndRecord,
        viewingSeatId = 1,
        revealForSeat = null,
        effectFacts = EffectProjectionFacts(),
        mechanicSourceFacts = MechanicSourceFacts(),
        abilityExhaustionFacts = exhaustionFacts,
        persistentFeedFacts = PersistentFeedFacts(),
    )
