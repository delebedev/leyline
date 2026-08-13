package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
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
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
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
            val targetIid = 100
            val crewVehicleIid = 101
            val crewSourceIid = 102
            val reconfigureIid = 103
            val saddleMountIid = 104
            val saddleSourceIid = 105
            val earthbendLayerIds = listOf(7002, 7003, 7004, 7005)
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
            val nextIdentity = first.mutations.instanceIdTransition.nextState
            val nextEffects = first.mutations.effectTransition.next
            val earthbend = nextEffects.earthbend.activeByTarget.getValue(cardId)
            val boost =
                nextEffects.effects.activeEffects.values
                    .single()
            val keyword =
                nextEffects.effects.activeKeywordEffects.values
                    .single()

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                first.mutations.effectTransition.next shouldBe second.mutations.effectTransition.next
                first.mutations.consumedEarthbendResolutions.map { it.version } shouldBe listOf(1)
                first.gsm.annotationsList.map(AnnotationInfo::shape) shouldBe
                    expectedTransientShapes(targetIid, reconfigureIid, earthbendLayerIds)
                first.gsm.persistentAnnotationsList.map(AnnotationInfo::shape) shouldBe
                    expectedPersistentShapes(
                        targetIid = targetIid,
                        crewVehicleIid = crewVehicleIid,
                        crewSourceIid = crewSourceIid,
                        reconfigureIid = reconfigureIid,
                        saddleMountIid = saddleMountIid,
                        saddleSourceIid = saddleSourceIid,
                    )
                first.mutations.persistentBatch.deletedIds shouldBe listOf(3)

                nextIdentity.forgeIdToInstanceId.entries.map { it.toPair() } shouldBe
                    listOf(
                        cardId to InstanceId(targetIid),
                        ForgeCardId(201) to InstanceId(crewVehicleIid),
                        ForgeCardId(202) to InstanceId(crewSourceIid),
                        ForgeCardId(205) to InstanceId(reconfigureIid),
                        ForgeCardId(203) to InstanceId(saddleMountIid),
                        ForgeCardId(204) to InstanceId(saddleSourceIid),
                    )
                nextIdentity.nextInstanceId shouldBe 106

                earthbend.layers.all shouldBe earthbendLayerIds
                earthbend.uniqueAbilityId shouldBe 200
                nextEffects.earthbend.nextUniqueAbilityId shouldBe 201
                boost.syntheticId shouldBe 7009
                keyword.keyword shouldBe "Flying"
                keyword.syntheticId shouldBe 7010
                nextEffects.crew.active shouldBe mapOf(ForgeCardId(201) to 7012)
                nextEffects.reconfigure.active shouldBe mapOf(ForgeCardId(205) to 7013)
                nextEffects.effects.nextId shouldBe 7014
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

private data class AnnotationShape(
    val id: Int,
    val affectorId: Int,
    val affectedIds: List<Int>,
    val types: List<AnnotationType>,
    val details: List<AnnotationDetailShape>,
)

private data class AnnotationDetailShape(
    val key: String,
    val type: KeyValuePairValueType,
    val uint32Values: List<Int>,
    val int32Values: List<Int>,
    val uint64Values: List<Long>,
    val int64Values: List<Long>,
    val boolValues: List<Boolean>,
    val stringValues: List<String>,
    val floatValues: List<Float>,
    val doubleValues: List<Double>,
)

private fun AnnotationInfo.shape(): AnnotationShape =
    AnnotationShape(
        id = id,
        affectorId = affectorId,
        affectedIds = affectedIdsList,
        types = typeList,
        details = detailsList.map(KeyValuePairInfo::shape),
    )

private fun KeyValuePairInfo.shape(): AnnotationDetailShape =
    AnnotationDetailShape(
        key = key,
        type = type,
        uint32Values = valueUint32List,
        int32Values = valueInt32List,
        uint64Values = valueUint64List,
        int64Values = valueInt64List,
        boolValues = valueBoolList,
        stringValues = valueStringList,
        floatValues = valueFloatList,
        doubleValues = valueDoubleList,
    )

private fun expectedAnnotation(
    id: Int,
    affectedId: Int,
    vararg types: AnnotationType,
    affectorId: Int = 0,
    details: List<Pair<String, Int>> = emptyList(),
): AnnotationShape =
    AnnotationShape(
        id = id,
        affectorId = affectorId,
        affectedIds = listOf(affectedId),
        types = types.toList(),
        details =
            details.map { (key, value) ->
                AnnotationDetailShape(
                    key = key,
                    type = KeyValuePairValueType.Int32,
                    uint32Values = emptyList(),
                    int32Values = listOf(value),
                    uint64Values = emptyList(),
                    int64Values = emptyList(),
                    boolValues = emptyList(),
                    stringValues = emptyList(),
                    floatValues = emptyList(),
                    doubleValues = emptyList(),
                )
            },
    )

private fun expectedTransientShapes(
    targetIid: Int,
    reconfigureIid: Int,
    earthbendLayerIds: List<Int>,
): List<AnnotationShape> =
    listOf(
        expectedAnnotation(
            50,
            targetIid,
            AnnotationType.PowerToughnessModCreated,
            affectorId = targetIid,
            details = listOf("power" to 0, "toughness" to 0),
        ),
        expectedAnnotation(51, 7006, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(52, 7007, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(53, 7008, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(54, 7006, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(55, 7007, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(56, 7008, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(
            57,
            targetIid,
            AnnotationType.PowerToughnessModCreated,
            affectorId = targetIid,
            details = listOf("power" to 2, "toughness" to 3),
        ),
        expectedAnnotation(58, earthbendLayerIds[0], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(59, earthbendLayerIds[1], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(60, earthbendLayerIds[2], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(61, earthbendLayerIds[3], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(62, 7009, AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(63, 7010, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(64, 7013, AnnotationType.LayeredEffectCreated, affectorId = reconfigureIid),
    )

private fun expectedPersistentShapes(
    targetIid: Int,
    crewVehicleIid: Int,
    crewSourceIid: Int,
    reconfigureIid: Int,
    saddleMountIid: Int,
    saddleSourceIid: Int,
): List<AnnotationShape> =
    listOf(
        expectedAnnotation(
            1,
            targetIid,
            AnnotationType.ModifiedToughness,
            AnnotationType.ModifiedPower,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("effect_id" to 7009),
        ),
        expectedAnnotation(
            2,
            targetIid,
            AnnotationType.AddAbility_af5a,
            AnnotationType.LayeredEffect,
            details =
                listOf(
                    "grpid" to 8,
                    "effect_id" to 7010,
                    "originalAbilityObjectZcid" to 0,
                    "UniqueAbilityId" to 7011,
                ),
        ),
        expectedAnnotation(
            4,
            targetIid,
            AnnotationType.AddAbility_af5a,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details =
                listOf(
                    "originalAbilityObjectZcid" to targetIid,
                    "UniqueAbilityId" to 200,
                    "grpid" to 9,
                    "sourceAbilityGRPID" to 555,
                    "effect_id" to 7003,
                ),
        ),
        expectedAnnotation(
            5,
            targetIid,
            AnnotationType.ModifiedPower,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("sourceAbilityGRPID" to 555, "effect_id" to 7004),
        ),
        expectedAnnotation(
            6,
            targetIid,
            AnnotationType.ModifiedToughness,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("sourceAbilityGRPID" to 555, "effect_id" to 7005),
        ),
        expectedAnnotation(
            7,
            crewSourceIid,
            AnnotationType.CrewedThisTurn,
            affectorId = crewVehicleIid,
        ),
        expectedAnnotation(
            8,
            saddleSourceIid,
            AnnotationType.SaddledThisTurn,
            affectorId = saddleMountIid,
        ),
        expectedAnnotation(
            9,
            crewVehicleIid,
            AnnotationType.ModifiedType,
            AnnotationType.LayeredEffect,
            details = listOf("effect_id" to 7012, "sourceAbilityGRPID" to 333),
        ),
        expectedAnnotation(
            10,
            reconfigureIid,
            AnnotationType.ModifiedType,
            AnnotationType.LayeredEffect,
            affectorId = reconfigureIid,
            details = listOf("effect_id" to 7013, "sourceAbilityGRPID" to 444),
        ),
        expectedAnnotation(
            11,
            targetIid,
            AnnotationType.Designation,
            affectorId = targetIid,
            details = listOf("DesignationType" to 23, "ControllerId" to 1),
        ),
    )
