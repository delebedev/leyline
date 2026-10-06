package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.ProjectionState
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class StateProjectionCompilerResolutionTest :
    FunSpec({
        tags(UnitTag)

        test("opening resolution orders owned effects and preserves foreign effect placement") {
            val source = ForgeCardId(10)
            val target = ForgeCardId(20)
            val resolving = stackAbility(source, 31)
            val snapshot = stackAbilitySnapshot(1, source, listOf(resolving, stackAbility(source, 30)), target)
            val opened =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot, events = FrameEventLog(listOf(GameEvent.CardAttached(source, target, SeatId(1))))).copy(
                        effectFacts =
                            EffectProjectionFacts(
                                boostEntries =
                                    listOf(
                                        EffectProjectionFacts.BoostEntry(
                                            target,
                                            1,
                                            2,
                                            1,
                                            1,
                                            sourceAbilityGrpId = resolving.grpId,
                                            sourceForgeCardId = source,
                                        ),
                                        EffectProjectionFacts.BoostEntry(
                                            target,
                                            3,
                                            4,
                                            2,
                                            2,
                                            sourceAbilityGrpId = 9004,
                                            sourceForgeCardId = target,
                                        ),
                                    ),
                            ),
                    ),
                    ProjectionState.initial(),
                    ViewerProjectionIntent.of(listOf(ProjectionSupplement.ResolutionStarted(resolving))),
                )
            val identities = opened.transition.nextState.identities.forgeIdToInstanceId
            val sourceId = identities.getValue(source).value
            val foreignId = identities.getValue(target).value
            val abilityId = identities.getValue(FrameIdResolver.triggerStackAbilityForgeId(31)).value
            val relevant = setOf(AnnotationType.ResolutionStart, AnnotationType.LayeredEffectCreated, AnnotationType.AttachmentCreated)
            assertSoftly {
                opened.gsm.annotationsList
                    .filter {
                        it.affectorId in setOf(sourceId, foreignId, abilityId) && it.typeList.any { type -> type in relevant }
                    }.map {
                        it.typeList.single() to
                            it.affectorId
                    } shouldContainExactly
                    listOf(
                        AnnotationType.LayeredEffectCreated to foreignId,
                        AnnotationType.ResolutionStart to abilityId,
                        AnnotationType.AttachmentCreated to sourceId,
                        AnnotationType.LayeredEffectCreated to sourceId,
                    )
                opened.gsm.annotationsList.count {
                    AnnotationType.ResolutionComplete in it.typeList ||
                        AnnotationType.AbilityInstanceDeleted in it.typeList
                } shouldBe
                    0
                opened.transition.nextState.annotations.openResolutions shouldBe setOf(abilityId)
            }
        }

        test("completion orders source effects before retirement after an earlier resolution start") {
            val source = ForgeCardId(10)
            val target = ForgeCardId(20)
            val resolving = stackAbility(source, 31)
            val snapshot = stackAbilitySnapshot(1, source, listOf(resolving), target)
            val intent = ViewerProjectionIntent.of(listOf(ProjectionSupplement.ResolutionStarted(resolving)))
            val opened =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot),
                    ProjectionState.initial(),
                    intent,
                )
            val finished =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(
                        snapshot,
                        snapshot,
                        FrameEventLog(
                            listOf(
                                GameEvent.CardAttached(source, target, SeatId(1)),
                                GameEvent.SpellResolved(
                                    source,
                                    false,
                                    isAbility = true,
                                    abilityForgeId = 31,
                                    abilityGrpId = resolving.grpId,
                                ),
                            ),
                        ),
                    ).copy(
                        effectFacts =
                            EffectProjectionFacts(
                                boostEntries =
                                    listOf(
                                        EffectProjectionFacts.BoostEntry(
                                            target,
                                            1,
                                            2,
                                            1,
                                            1,
                                            sourceAbilityGrpId = resolving.grpId,
                                            sourceForgeCardId = source,
                                        ),
                                    ),
                            ),
                    ),
                    opened.transition.nextState,
                    intent,
                )
            val annotations = finished.gsm.annotationsList
            val relevant =
                setOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.AttachmentCreated,
                    AnnotationType.LayeredEffectCreated,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.AbilityInstanceDeleted,
                )
            assertSoftly {
                annotations.flatMap { it.typeList }.filter { it in relevant } shouldContainExactly
                    listOf(
                        AnnotationType.AttachmentCreated,
                        AnnotationType.LayeredEffectCreated,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.AbilityInstanceDeleted,
                    )
                val abilityId =
                    opened.gsm.annotationsList
                        .single { AnnotationType.ResolutionStart in it.typeList }
                        .affectorId
                annotations.single { AnnotationType.ResolutionComplete in it.typeList }.affectorId shouldBe abilityId
                annotations.single { AnnotationType.AbilityInstanceDeleted in it.typeList }.affectedIdsList shouldBe listOf(abilityId)
                annotations.map { it.id } shouldContainExactly (annotations.first().id..annotations.last().id).toList()
                finished.transition.nextState.persistentAnnotations.nextAnnotationId shouldBe annotations.last().id + 1
                finished.transition.nextState.annotations.openResolutions shouldBe emptySet()
            }
        }

        test("zero ability id resolution uses the projected source surrogate") {
            val source = ForgeCardId(10)
            val resolving = stackAbility(source, 0)
            val snapshot = stackAbilitySnapshot(1, source, listOf(resolving))
            val opened =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot),
                    ProjectionState.initial(),
                    ViewerProjectionIntent.of(listOf(ProjectionSupplement.ResolutionStarted(resolving))),
                )
            val identities = opened.transition.nextState.identities.forgeIdToInstanceId
            val abilityId = identities.getValue(FrameIdResolver.stackAbilityForgeId(source))
            assertSoftly {
                opened.gsm.annotationsList
                    .single { AnnotationType.ResolutionStart in it.typeList }
                    .affectorId shouldBe abilityId.value
                opened.gsm.gameObjectsList
                    .single { it.instanceId == abilityId.value }
                    .type shouldBe GameObjectType.Ability
                identities.containsKey(FrameIdResolver.triggerStackAbilityForgeId(0)) shouldBe false
                opened.transition.nextState.identities.nextInstanceId shouldBe abilityId.value + 1
                opened.transition.nextState.annotations.openResolutions shouldBe setOf(abilityId.value)
            }
        }
    })
