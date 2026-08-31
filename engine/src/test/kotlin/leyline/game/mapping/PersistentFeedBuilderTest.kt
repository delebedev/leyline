package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferResult
import leyline.game.codes.QualificationType
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.snapshot.AltCostBinding
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.FaceDownForetellKind
import leyline.game.state.HolderRecord
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.PromptFactKey
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.QualificationKind
import leyline.game.state.TemporaryPermanentKind

class PersistentFeedBuilderTest :
    FunSpec({

        tags(UnitTag)

        test("delayed trigger affectees remain fed while their ability is on the stack") {
            val affectees =
                AnnotationBuilder
                    .delayedTriggerAffectees(
                        triggerHolderId = InstanceId(124),
                        tokenInstanceIds = listOf(InstanceId(55)),
                        abilityGrpId = GrpId(136220),
                        removesFromZone = null,
                    ).toBuilder()
                    .setId(10)
                    .build()

            val retained =
                PersistentFeedBuilder.retainDelayedTriggerAffectees(
                    feeds = PersistentFeedSet(),
                    activeAnnotations = listOf(affectees),
                    holderIids = setOf(124),
                )

            retained[DelayedTriggerAffecteesKind] shouldBe listOf(affectees)
        }

        test("delayed trigger affectees stop feeding after their ability leaves the stack") {
            val affectees =
                AnnotationBuilder
                    .delayedTriggerAffectees(
                        triggerHolderId = InstanceId(124),
                        tokenInstanceIds = listOf(InstanceId(55)),
                        abilityGrpId = GrpId(136220),
                        removesFromZone = null,
                    ).toBuilder()
                    .setId(10)
                    .build()

            val retained =
                PersistentFeedBuilder.retainDelayedTriggerAffectees(
                    feeds = PersistentFeedSet(),
                    activeAnnotations = listOf(affectees),
                    holderIids = emptySet(),
                )

            retained[DelayedTriggerAffecteesKind].shouldBeEmpty()
        }

        test("non-foretold cards do not feed the persistent Foretell face-down row") {
            val foretold = ForgeCardId(10)
            val ordinaryExile = ForgeCardId(20)
            val snapshot =
                GsmSnapshot.forTest(
                    boundCards =
                        mapOf(
                            foretold to
                                BoundCard(
                                    foretold,
                                    CardSnapshot(
                                        foretold,
                                        "Foretold",
                                        1000,
                                        SeatId(1),
                                        SeatId(1),
                                        isForetold = true,
                                    ),
                                    data = null,
                                ),
                            ordinaryExile to
                                BoundCard(
                                    ordinaryExile,
                                    CardSnapshot(
                                        ordinaryExile,
                                        "Ordinary exile",
                                        2000,
                                        SeatId(1),
                                        SeatId(1),
                                    ),
                                    data = null,
                                ),
                        ),
                )

            val projected = projectPersistentFrame(ProjectionState.initial(), snapshot, PromptProjectionFacts(), PersistentFeedFacts())

            projected.result.feeds[FaceDownForetellKind] shouldBe
                listOf(
                    AnnotationBuilder.faceDownPersistent(
                        instanceId = InstanceId(100),
                        reason = AnnotationConstants.FACEDOWN_REASON_FORETELL,
                        abilityGrpId = GrpId(KeywordAbilityIds.FORETELL),
                    ),
                )
        }

        test("two value-only frames produce exact feeds holders and identity state without mutating prior") {
            val source = ForgeCardId(10)
            val token = ForgeCardId(20)
            val holderForgeId = FrameIdResolver.delayedTriggerHolderForgeId(source)
            val promptKey = PromptFactKey(SeatId(1), 7)
            val snapshot =
                GsmSnapshot.forTest(
                    boundCards =
                        mapOf(
                            source to
                                BoundCard(
                                    source,
                                    CardSnapshot(source, "Source", 1000, SeatId(1), SeatId(1), isOnBattlefield = true),
                                    data = null,
                                    altCosts =
                                        listOf(
                                            AltCostBinding(
                                                KeywordAbilityIds.MOBILIZE,
                                                abilityGrpId = 701,
                                                manaCost = emptyList(),
                                            ),
                                        ),
                                    mobilizeCleanup = 700,
                                ),
                            token to
                                BoundCard(
                                    token,
                                    CardSnapshot(
                                        token,
                                        "Token",
                                        2000,
                                        SeatId(1),
                                        SeatId(1),
                                        isOnBattlefield = true,
                                        isToken = true,
                                        endOfTurnLeavePlay = true,
                                    ),
                                    data = null,
                                ),
                        ),
                )
            val promptFacts =
                PromptProjectionFacts(
                    collectEvidenceCosts =
                        listOf(
                            PromptProjectionFacts.CollectEvidenceFact(
                                promptKey,
                                leyline.game.state.CollectEvidenceCost(source, threshold = 4),
                            ),
                        ),
                )
            val facts =
                PersistentFeedFacts(
                    combatQualifications =
                        listOf(
                            PersistentFeedFacts.CombatQualificationRow(
                                source,
                                token,
                                source,
                                abilityGrpId = 800,
                                qualificationType = QualificationType.CantBlock,
                            ),
                        ),
                    collectEvidence =
                        listOf(
                            PersistentFeedFacts.CollectEvidenceDisplay(
                                promptKey,
                                source,
                                threshold = 4,
                                graveyardManaValue = 5,
                                abilityGrpId = 900,
                            ),
                        ),
                    endStepTokenSources = listOf(PersistentFeedFacts.EndStepTokenSource(token, source)),
                )
            val prior = ProjectionState.initial()
            val first = projectPersistentFrame(prior, snapshot, promptFacts, facts)
            val retry = projectPersistentFrame(prior, snapshot, promptFacts, facts)

            assertSoftly {
                first shouldBe retry
                prior shouldBe ProjectionState.initial()
                first.result.feeds[QualificationKind] shouldBe
                    listOf(
                        AnnotationBuilder.qualification(
                            affectorId = InstanceId(100),
                            instanceId = InstanceId(101),
                            grpId = GrpId(800),
                            qualificationType = QualificationType.CantBlock,
                            sourceParent = InstanceId(100),
                        ),
                    )
                first.result.feeds[AbilityWordActiveKind] shouldBe
                    listOf(
                        AnnotationBuilder.abilityWordActive(
                            instanceId = InstanceId(100),
                            abilityWordName = "CollectEvidenceCount",
                            value = 5,
                            threshold = 4,
                            abilityGrpId = GrpId(900),
                        ),
                    )
                first.result.feeds[TemporaryPermanentKind] shouldBe
                    listOf(
                        AnnotationBuilder.temporaryPermanent(
                            tokenInstanceId = InstanceId(101),
                            abilityGrpId = GrpId(700),
                            affectorId = InstanceId(102),
                        ),
                    )
                first.result.feeds[DelayedTriggerAffecteesKind] shouldBe
                    listOf(
                        AnnotationBuilder.delayedTriggerAffectees(
                            triggerHolderId = InstanceId(102),
                            tokenInstanceIds = listOf(InstanceId(101)),
                            abilityGrpId = GrpId(700),
                        ),
                    )
                first.result.currentHolders shouldBe
                    listOf(
                        HolderRecord(
                            iid = 102,
                            ownerSeat = 1,
                            objectSourceGrpId = 701,
                            parentIid = 100,
                            cleanupGrpId = 700,
                        ),
                    )
                first.next.identities.forgeIdToInstanceId shouldContainExactly
                    mapOf(source to InstanceId(100), token to InstanceId(101), holderForgeId to InstanceId(102))
            }

            val second = projectPersistentFrame(first.next, GsmSnapshot.forTest(), PromptProjectionFacts(), PersistentFeedFacts())
            assertSoftly {
                second.result.currentHolders.shouldBeEmpty()
                second.next.delayedTriggerHolders shouldBe emptyMap()
                second.next.identities shouldBe first.next.identities
            }
        }

        test("missing cut rows deterministically omit specialized feeds") {
            val token = ForgeCardId(30)
            val snapshot =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            token to
                                CardSnapshot(
                                    token,
                                    "Token",
                                    3000,
                                    SeatId(1),
                                    SeatId(1),
                                    isOnBattlefield = true,
                                    isToken = true,
                                    endOfTurnLeavePlay = true,
                                ),
                        ),
                )

            val projected = projectPersistentFrame(ProjectionState.initial(), snapshot, PromptProjectionFacts(), PersistentFeedFacts())

            assertSoftly {
                projected.result.feeds[QualificationKind].shouldBeEmpty()
                projected.result.feeds[AbilityWordActiveKind].shouldBeEmpty()
                projected.result.feeds[DelayedTriggerAffecteesKind].shouldBeEmpty()
                projected.result.currentHolders.shouldBeEmpty()
                projected.result.feeds[TemporaryPermanentKind] shouldBe
                    listOf(
                        AnnotationBuilder.temporaryPermanent(
                            tokenInstanceId = InstanceId(100),
                            abilityGrpId = leyline.game.annotations.AnnotationConstants.EOT_SACRIFICE_GRP_ID,
                            affectorId = InstanceId(100),
                        ),
                    )
            }
        }

        test("persistent feed facts freeze caller-owned outer and nested lists") {
            val blockerIds = mutableListOf(ForgeCardId(2))
            val combatRows =
                mutableListOf(
                    PersistentFeedFacts.CombatQualificationRow(
                        ForgeCardId(1),
                        ForgeCardId(2),
                        ForgeCardId(1),
                        abilityGrpId = 3,
                        qualificationType = QualificationType.CantBlock,
                        cantBlockForgeIds = blockerIds,
                    ),
                )
            val facts = PersistentFeedFacts(combatQualifications = combatRows)

            combatRows.clear()
            blockerIds.clear()

            assertSoftly {
                facts.combatQualifications.size shouldBe 1
                facts.combatQualifications.single().cantBlockForgeIds shouldBe listOf(ForgeCardId(2))
                shouldThrow<UnsupportedOperationException> {
                    (facts.combatQualifications as MutableList).clear()
                }
                shouldThrow<UnsupportedOperationException> {
                    (facts.combatQualifications.single().cantBlockForgeIds as MutableList).clear()
                }
            }
        }
    })

private data class PersistentFrameProjection(
    val result: PersistentFeedBuildResult,
    val next: ProjectionState,
)

private fun projectPersistentFrame(
    prior: ProjectionState,
    snapshot: GsmSnapshot,
    promptFacts: PromptProjectionFacts,
    facts: PersistentFeedFacts,
): PersistentFrameProjection {
    val editor = prior.editor()
    val result =
        PersistentFeedBuilder.build(
            events = emptyList<GameEvent>(),
            snap = snapshot,
            prev = null,
            frameIds = FrameIdResolver(editor.identities),
            decayedCleanupSourcesThisGsm = emptySet(),
            transferResult = TransferResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            promptFacts = promptFacts,
            persistentFeedFacts = facts,
            references = ProjectionCardReferences(InMemoryCardRepository()),
        )
    editor.delayedTriggerHolders.clear()
    result.currentHolders.forEach { editor.delayedTriggerHolders[it.iid] = it }
    return PersistentFrameProjection(result, editor.freeze())
}
