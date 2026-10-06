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
