package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.conformance.detailInt
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
 * Phase 2.5 — adoption of [GameBridge.abilityLineage] inside
 * [StateMapper.emitTriggerLifecycleAnnotationsForTest]. Cast looks up the
 * cast-time identity (entry left in place); resolve consumes the entry
 * (ability finished). When no lineage exists the emission falls through to
 * [FrameIdResolver]/snap-derived ids.
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

        test("cast with lineage record uses lineage abilityIid + sourceIidAtCreate (entry not consumed)") {
            val bridge = stubBridge()
            val sourceForge = ForgeCardId(13)
            val abilityForgeId = 77

            val preTransformIid = bridge.getOrAllocInstanceId(sourceForge).value
            val recordedAbilityIid = InstanceId(100_077)
            val recordedSourceZone = ZoneIds.BATTLEFIELD
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityForgeId = abilityForgeId,
                    abilityIid = recordedAbilityIid,
                    sourceForgeId = sourceForge,
                    sourceIidAtCreate = InstanceId(preTransformIid),
                    sourceZoneAtCreate = recordedSourceZone,
                    abilityGrpId = 0,
                ),
            )

            // Mutate bridge state after lineage was recorded — different post-cast iid.
            val realloc = bridge.reallocInstanceId(sourceForge)
            realloc.new.value shouldNotBe preTransformIid

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
                aic.affectorId shouldBe preTransformIid
                aic.affectedIdsList shouldBe listOf(recordedAbilityIid.value)
                aic.detailInt(DetailKeys.SOURCE_ZONE) shouldBe recordedSourceZone

                val triggering = transferPersistent[0]
                triggering.typeList shouldBe listOf(AnnotationType.TriggeringObject)
                triggering.affectorId shouldBe recordedAbilityIid.value
                triggering.affectedIdsList shouldBe listOf(preTransformIid)
                triggering.detailInt(DetailKeys.SOURCE_ZONE) shouldBe recordedSourceZone

                // Cast uses lookup, not consume — entry must remain for the upcoming resolve.
                bridge.abilityLineage.lookup(abilityForgeId) shouldNotBe null
            }
        }
    })
