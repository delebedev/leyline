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
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.EarthbendTracker
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

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
                    effectFacts = EffectProjectionFacts(),
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

        test("zero-Forge state-frame input compiles every scoped effect family from facts") {
            val cardId = ForgeCardId(101)
            val signature = EarthbendTracker.Signature(timestamp = 10, staticId = 11)
            val card =
                CardSnapshot(
                    forgeCardId = cardId,
                    name = "Forest",
                    grpId = 100,
                    owner = SeatId(1),
                    controller = SeatId(1),
                    isLand = true,
                    isOnBattlefield = true,
                    netPower = 0,
                    netToughness = 0,
                )
            val snapshot =
                GsmSnapshot.forTest(
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
            val facts =
                EffectProjectionFacts(
                    boostEntries =
                        listOf(
                            EffectProjectionFacts.BoostEntry(cardId, 1, 2, power = 2, toughness = 3),
                        ),
                    keywordEntries =
                        listOf(
                            EffectProjectionFacts.KeywordEntry(cardId, 10, 11, "Haste"),
                            EffectProjectionFacts.KeywordEntry(cardId, 12, 13, "Flying"),
                        ),
                    crewStates =
                        listOf(
                            EffectProjectionFacts.CrewState(
                                ForgeCardId(201),
                                listOf(ForgeCardId(202)),
                                isCreature = true,
                                crewAbilityGrpId = 333,
                            ),
                        ),
                    saddleStates =
                        listOf(
                            EffectProjectionFacts.SaddleState(ForgeCardId(203), listOf(ForgeCardId(204))),
                        ),
                    reconfigureStates =
                        listOf(
                            EffectProjectionFacts.ReconfigureState(
                                forgeCardId = ForgeCardId(205),
                                isAttached = true,
                                isCreature = false,
                                attachAbilityGrpId = 444,
                            ),
                        ),
                    pendingEarthbendResolutions =
                        listOf(
                            EffectProjectionFacts.PendingEarthbendResolution(
                                version = 1,
                                sourceCardId = cardId,
                                sourceAbilityGrpId = 555,
                                abilityForgeId = 0,
                                targetCardIds = listOf(cardId),
                            ),
                        ),
                    battlefieldEarthbendSignatures =
                        listOf(
                            EffectProjectionFacts.BattlefieldEarthbendSignature(cardId, signature),
                        ),
                )
            val input =
                StateFrameInput(
                    gameStateId = 1,
                    snapshot = snapshot,
                    previousSnapshot = null,
                    events = FrameEventLog.EMPTY,
                    promptFacts = PromptProjectionFacts(),
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = 1,
                    revealForSeat = null,
                    effectFacts = facts,
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val first = StateMapper.buildDiff(input, "state-frame", bridge).finalizeAnnotations()
            val second = StateMapper.buildDiff(input, "state-frame", bridge).finalizeAnnotations()

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                first.mutations.effectTransition.next shouldBe second.mutations.effectTransition.next
                first.mutations.consumedEarthbendResolutions.map { it.version } shouldBe listOf(1)
                first.mutations.effectTransition.next.crew.active.keys shouldBe setOf(ForgeCardId(201))
                first.mutations.effectTransition.next.reconfigure.active.keys shouldBe setOf(ForgeCardId(205))
                first.mutations.effectTransition.next.earthbend.activeByTarget.keys shouldBe setOf(cardId)
                first.mutations.effectTransition.next.earthbend.activeByTarget
                    .getValue(cardId)
                    .layers.all shouldBe listOf(7002, 7003, 7004, 7005)
                first.mutations.effectTransition.next.earthbend.activeByTarget
                    .getValue(cardId)
                    .uniqueAbilityId shouldBe 200
                first.mutations.effectTransition.next.effects.activeEffects
                    .values
                    .map { it.syntheticId } shouldBe listOf(7009)
                first.mutations.effectTransition.next.effects.activeKeywordEffects
                    .values
                    .map { it.keyword } shouldBe listOf("Flying")
                first.mutations.effectTransition.next.effects.activeKeywordEffects
                    .values
                    .map { it.syntheticId } shouldBe listOf(7010)
                first.mutations.effectTransition.next.crew.active shouldBe mapOf(ForgeCardId(201) to 7012)
                first.mutations.effectTransition.next.reconfigure.active shouldBe mapOf(ForgeCardId(205) to 7013)
            }
        }

        test("effect facts snapshot caller collections") {
            val crewSources = mutableListOf(ForgeCardId(202))
            val targetIds = mutableListOf(ForgeCardId(301))
            val facts =
                EffectProjectionFacts(
                    crewStates =
                        listOf(
                            EffectProjectionFacts.CrewState(
                                vehicleForgeCardId = ForgeCardId(201),
                                crewSourceForgeCardIds = crewSources,
                                isCreature = true,
                                crewAbilityGrpId = null,
                            ),
                        ),
                    pendingEarthbendResolutions =
                        listOf(
                            EffectProjectionFacts.PendingEarthbendResolution(
                                version = 1,
                                sourceCardId = ForgeCardId(10),
                                sourceAbilityGrpId = 900,
                                abilityForgeId = 0,
                                targetCardIds = targetIds,
                            ),
                        ),
                )

            crewSources += ForgeCardId(203)
            targetIds += ForgeCardId(302)

            assertSoftly {
                facts.crewStates.single().crewSourceForgeCardIds shouldBe listOf(ForgeCardId(202))
                facts.pendingEarthbendResolutions.single().targetCardIds shouldBe listOf(ForgeCardId(301))
            }
        }
    })
