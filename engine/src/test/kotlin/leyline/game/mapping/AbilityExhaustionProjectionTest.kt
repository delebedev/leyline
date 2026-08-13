package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.codes.DetailKeys
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PromptProjectionFacts
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class AbilityExhaustionProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("zero-Forge facts replay exact ordered persistent lifecycle") {
            val firstCardId = ForgeCardId(201)
            val secondCardId = ForgeCardId(202)
            val firstSnapshot = exhaustionSnapshot(firstCardId, secondCardId, gameStateId = 1)
            val firstFacts =
                AbilityExhaustionFacts(
                    listOf(
                        AbilityExhaustionFacts.Row(firstCardId, abilityGrpId = 7001, usesRemaining = 0, uniqueAbilityId = 50),
                        AbilityExhaustionFacts.Row(secondCardId, abilityGrpId = 7002, usesRemaining = 2, uniqueAbilityId = 374),
                    ),
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val firstInput = exhaustionFrame(firstSnapshot, previous = null, facts = firstFacts)
            val cacheBefore = bridge.cachedAbilityRegistryCardIds()

            val first = StateMapper.buildDiff(firstInput, "ability-exhaustion", bridge).finalizeAnnotations()
            val retry = StateMapper.buildDiff(firstInput, "ability-exhaustion", bridge).finalizeAnnotations()
            val firstRows = first.gsm.persistentAnnotationsList.filter { AnnotationType.AbilityExhausted in it.typeList }

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe retry.gsm.toByteArray().toList()
                first.transition shouldBe retry.transition
                firstRows.map { it.affectedIdsList.single() } shouldContainExactly listOf(100, 101)
                firstRows.map { it.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) } shouldContainExactly listOf(7001, 7002)
                firstRows.map { it.detailInt(DetailKeys.USES_REMAINING) } shouldContainExactly listOf(0, 2)
                firstRows.map { it.detailInt(DetailKeys.UNIQUE_ABILITY_ID) } shouldContainExactly listOf(50, 374)
                firstRows.map { it.id } shouldContainExactly listOf(1, 2)
                bridge.cachedAbilityRegistryCardIds() shouldBe cacheBefore
            }

            bridge.commitProjection(checkNotNull(first.transition))
            val changedSnapshot = exhaustionSnapshot(firstCardId, secondCardId, 2)
            val changedInput =
                exhaustionFrame(
                    snapshot = changedSnapshot,
                    previous = first.projectionSnapshot,
                    projectionState = checkNotNull(first.transition).nextState,
                    facts =
                        AbilityExhaustionFacts(
                            listOf(AbilityExhaustionFacts.Row(firstCardId, 7001, usesRemaining = 1, uniqueAbilityId = 50)),
                        ),
                )
            val changed = StateMapper.buildDiff(changedInput, "ability-exhaustion", bridge).finalizeAnnotations()
            val changedRetry = StateMapper.buildDiff(changedInput, "ability-exhaustion", bridge).finalizeAnnotations()

            assertSoftly {
                changed.gsm.toByteArray().toList() shouldBe changedRetry.gsm.toByteArray().toList()
                changed.gsm.diffDeletedPersistentAnnotationIdsList shouldContainExactly listOf(2, 1)
                changed.gsm.persistentAnnotationsList.single().let { row ->
                    row.id shouldBe 3
                    row.affectedIdsList shouldContainExactly listOf(100)
                    row.detailInt(DetailKeys.USES_REMAINING) shouldBe 1
                }
                bridge.cachedAbilityRegistryCardIds() shouldBe cacheBefore
            }

            bridge.commitProjection(checkNotNull(changed.transition))
            val deletionSnapshot = exhaustionSnapshot(firstCardId, secondCardId, 3)
            val deletion =
                StateMapper
                    .buildDiff(
                        exhaustionFrame(
                            deletionSnapshot,
                            changed.projectionSnapshot,
                            AbilityExhaustionFacts(),
                            checkNotNull(changed.transition).nextState,
                        ),
                        "ability-exhaustion",
                        bridge,
                    ).finalizeAnnotations()

            assertSoftly {
                deletion.gsm.persistentAnnotationsList shouldBe emptyList()
                deletion.gsm.diffDeletedPersistentAnnotationIdsList shouldContainExactly listOf(3)
                bridge.cachedAbilityRegistryCardIds() shouldBe cacheBefore
            }
        }
    })

private fun exhaustionSnapshot(
    firstCardId: ForgeCardId,
    secondCardId: ForgeCardId,
    gameStateId: Int,
): GsmSnapshot {
    val cards =
        linkedMapOf(
            firstCardId to exhaustionCard(firstCardId, "First"),
            secondCardId to exhaustionCard(secondCardId, "Second"),
        )
    return GsmSnapshot.forTest(
        matchId = "ability-exhaustion",
        gameStateId = gameStateId,
        objects = cards,
        zones =
            mapOf(
                ZoneIds.BATTLEFIELD to
                    ZoneSnapshot(
                        id = ZoneIds.BATTLEFIELD,
                        type = ZoneType.Battlefield,
                        owner = null,
                        visibility = Visibility.Public,
                        contents = cards.keys.toList(),
                    ),
            ),
    )
}

private fun exhaustionCard(
    cardId: ForgeCardId,
    name: String,
): CardSnapshot =
    CardSnapshot(
        forgeCardId = cardId,
        name = name,
        grpId = cardId.value,
        owner = SeatId(1),
        controller = SeatId(1),
        isOnBattlefield = true,
        netPower = 1,
        netToughness = 1,
    )

private fun exhaustionFrame(
    snapshot: GsmSnapshot,
    previous: GsmSnapshot?,
    facts: AbilityExhaustionFacts,
    projectionState: leyline.game.state.ProjectionState =
        leyline.game.state.ProjectionState
            .initial(),
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
        abilityExhaustionFacts = facts,
        projectionState = projectionState,
    )
