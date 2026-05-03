package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.conformance.detailUint
import leyline.game.InMemoryCardRepository
import leyline.game.codes.DetailKeys
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityWireIdentity
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Adoption of [GameBridge.abilityLineage] inside
 * [StateMapper.emitTriggerLifecycleAnnotationsForTest]. Cast emits using
 * frameIds-derived iids and writes a lineage entry when none exists. Resolve
 * consumes the entry to close AID against the cast-time source identity even
 * when the host card mutated during resolution. When no lineage entry exists
 * the emission falls through to [FrameIdResolver]/snap-derived ids.
 */
class EmitTriggerLifecycleAnnotationsTest :
    FunSpec({
        tags(UnitTag)

        fun stubBridge(): GameBridge = GameBridge(cardRepository = InMemoryCardRepository())

        fun emit(
            bridge: GameBridge,
            events: List<GameEvent>,
            snapshotSourceIids: Set<Int> = emptySet(),
            snapshotDisappearanceIids: Set<Int> = emptySet(),
            snap: GsmSnapshot = GsmSnapshot.forTest(),
        ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
            val annotations = mutableListOf<AnnotationInfo>()
            val transferPersistent = mutableListOf<AnnotationInfo>()
            StateMapper.emitTriggerLifecycleAnnotationsForTest(
                events = events,
                snapshotSourceIids = snapshotSourceIids,
                snapshotDisappearanceIids = snapshotDisappearanceIids,
                annotations = annotations,
                transferPersistent = transferPersistent,
                bridge = bridge,
                snap = snap,
                frameIds = FrameIdResolver(bridge),
            )
            return annotations to transferPersistent
        }

        test("resolve with lineage record uses pre-transform identity (chapter-III + transform regression)") {
            val bridge = stubBridge()
            val sourceForge = ForgeCardId(7)
            val abilityForgeId = 42

            // Pre-bind source forge to its initial (pre-transform) iid.
            val preTransformIid = bridge.getOrAllocInstanceId(sourceForge).value

            // Record lineage with the pre-transform iid + a known abilityIid + abilityGrpId.
            val recordedAbilityIid = InstanceId(100_042)
            val recordedAbilityGrpId = 99_999
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityForgeId = abilityForgeId,
                    abilityIid = recordedAbilityIid,
                    sourceForgeId = sourceForge,
                    sourceIidAtCreate = InstanceId(preTransformIid),
                    sourceZoneAtCreate = ZoneIds.BATTLEFIELD,
                    abilityGrpId = recordedAbilityGrpId,
                ),
            )

            // Simulate post-resolve transform: realloc the source forge to a new iid.
            val realloc = bridge.reallocInstanceId(sourceForge)
            val postTransformIid = realloc.new.value
            postTransformIid shouldNotBe preTransformIid

            val (annotations, _) =
                emit(
                    bridge = bridge,
                    events =
                        listOf(
                            GameEvent.SpellResolved(
                                cardId = sourceForge,
                                hasFizzled = false,
                                isTrigger = true,
                                abilityForgeId = abilityForgeId,
                            ),
                        ),
                )

            // Resolve emits RS, RC, AID — no AIC (cast already happened) and AID
            // not gated by snapshotDisappearanceIids here (empty set).
            assertSoftly {
                annotations shouldHaveSize 3

                val rs = annotations[0]
                rs.typeList shouldBe listOf(AnnotationType.ResolutionStart)
                rs.affectorId shouldBe recordedAbilityIid.value
                rs.affectedIdsList shouldBe listOf(recordedAbilityIid.value)
                rs.detailUint(DetailKeys.GRPID) shouldBe recordedAbilityGrpId

                val rc = annotations[1]
                rc.typeList shouldBe listOf(AnnotationType.ResolutionComplete)
                rc.affectorId shouldBe recordedAbilityIid.value
                rc.detailUint(DetailKeys.GRPID) shouldBe recordedAbilityGrpId

                val aid = annotations[2]
                aid.typeList shouldBe listOf(AnnotationType.AbilityInstanceDeleted)
                aid.affectedIdsList shouldBe listOf(recordedAbilityIid.value)
                // Critical: AID.affectorId is the PRE-transform source iid (from
                // lineage), not the post-transform iid the bridge currently maps.
                aid.affectorId shouldBe preTransformIid
                aid.affectorId shouldNotBe postTransformIid

                // Lineage entry consumed on resolve.
                bridge.abilityLineage.lookup(abilityForgeId) shouldBe null
            }
        }

        test("resolve without lineage falls through to snap-derived identity") {
            val bridge = stubBridge()
            val sourceForge = ForgeCardId(11)
            val unrecordedAbilityForgeId = 99
            val sourceIid = bridge.getOrAllocInstanceId(sourceForge).value
            val expectedAbilityIid = FrameIdResolver(bridge).stackAbilityIid(sourceForge).value

            val (annotations, _) =
                emit(
                    bridge = bridge,
                    events =
                        listOf(
                            GameEvent.SpellResolved(
                                cardId = sourceForge,
                                hasFizzled = false,
                                isTrigger = true,
                                abilityForgeId = unrecordedAbilityForgeId,
                            ),
                        ),
                )

            assertSoftly {
                annotations shouldHaveSize 3

                val rs = annotations[0]
                rs.typeList shouldBe listOf(AnnotationType.ResolutionStart)
                rs.affectorId shouldBe expectedAbilityIid

                val aid = annotations[2]
                aid.typeList shouldBe listOf(AnnotationType.AbilityInstanceDeleted)
                aid.affectedIdsList shouldBe listOf(expectedAbilityIid)
                // Falls through to source-card-keyed snap identity.
                aid.affectorId shouldBe sourceIid
            }
        }

        test("cast emits AIC + persistent TriggeringObject from frameIds and records lineage when absent") {
            val bridge = stubBridge()
            val sourceForge = ForgeCardId(13)
            val abilityForgeId = 77

            val sourceIid = bridge.getOrAllocInstanceId(sourceForge).value
            val expectedAbilityIid = FrameIdResolver(bridge).stackAbilityIid(sourceForge).value

            // No pre-existing lineage entry — the cast-half records one using
            // frameIds-derived iids so the resolve-half can close against the
            // cast-time identity.
            bridge.abilityLineage.lookup(abilityForgeId) shouldBe null

            val (annotations, transferPersistent) =
                emit(
                    bridge = bridge,
                    events =
                        listOf(
                            GameEvent.SpellCast(
                                cardId = sourceForge,
                                seatId = leyline.bridge.types.SeatId(1),
                                isTrigger = true,
                                abilityForgeId = abilityForgeId,
                            ),
                        ),
                )

            assertSoftly {
                annotations shouldHaveSize 1
                transferPersistent shouldHaveSize 1

                val aic = annotations[0]
                aic.typeList shouldBe listOf(AnnotationType.AbilityInstanceCreated)
                aic.affectorId shouldBe sourceIid
                aic.affectedIdsList shouldBe listOf(expectedAbilityIid)

                val triggering = transferPersistent[0]
                triggering.typeList shouldBe listOf(AnnotationType.TriggeringObject)
                triggering.affectorId shouldBe expectedAbilityIid
                triggering.affectedIdsList shouldBe listOf(sourceIid)

                // Lineage entry written by cast-half — abilityGrpId is 0 here
                // because GameEvent.SpellCast on this branch doesn't carry it
                // (the field arrives via a follow-up branch); resolve falls
                // back to abilityGrpIdForSource(snap) when this is 0.
                val recorded = bridge.abilityLineage.lookup(abilityForgeId)
                recorded shouldNotBe null
                recorded!!.sourceIidAtCreate.value shouldBe sourceIid
                recorded.abilityIid.value shouldBe expectedAbilityIid
                recorded.abilityGrpId shouldBe 0
            }
        }

        test("cast does not overwrite an existing lineage entry") {
            val bridge = stubBridge()
            val sourceForge = ForgeCardId(21)
            val abilityForgeId = 99
            val preexistingAbilityIid = InstanceId(100_099)
            val preexistingSourceIid = InstanceId(42)
            val preexistingGrpId = 8_888

            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityForgeId = abilityForgeId,
                    abilityIid = preexistingAbilityIid,
                    sourceForgeId = sourceForge,
                    sourceIidAtCreate = preexistingSourceIid,
                    sourceZoneAtCreate = ZoneIds.BATTLEFIELD,
                    abilityGrpId = preexistingGrpId,
                ),
            )

            emit(
                bridge = bridge,
                events =
                    listOf(
                        GameEvent.SpellCast(
                            cardId = sourceForge,
                            seatId = leyline.bridge.types.SeatId(1),
                            isTrigger = true,
                            abilityForgeId = abilityForgeId,
                        ),
                    ),
            )

            val recorded = bridge.abilityLineage.lookup(abilityForgeId)
            assertSoftly {
                recorded shouldNotBe null
                recorded!!.abilityIid shouldBe preexistingAbilityIid
                recorded.sourceIidAtCreate shouldBe preexistingSourceIid
                recorded.abilityGrpId shouldBe preexistingGrpId
            }
        }
    })
