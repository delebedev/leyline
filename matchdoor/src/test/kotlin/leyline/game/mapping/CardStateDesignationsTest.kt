package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.DesignationSet
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PlottedRole
import leyline.game.snapshot.PreparedRole
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit pins for the [CardStateDesignations] table — covers each row's mode
 * dispatch (gain emit, lose emit, position relative to Resolve ZT) without
 * needing a Forge boot. Conformance-level coverage of Prepared / Plotted /
 * Foretold lifecycle lives in [HonorboundPagePrepareTest], [PlotTest],
 * [ForetellTest], and [BoundCardParityTest] respectively.
 */
class CardStateDesignationsTest :
    FunSpec({
        tags(UnitTag)

        fun cardSnap(fid: Int): CardSnapshot =
            CardSnapshot(
                forgeCardId = ForgeCardId(fid),
                name = "card-$fid",
                grpId = fid * 10,
                owner = SeatId(1),
                controller = SeatId(1),
            )

        fun bound(
            fid: Int,
            designations: DesignationSet,
        ): BoundCard =
            BoundCard(
                forgeCardId = ForgeCardId(fid),
                snapshot = cardSnap(fid),
                data = null,
                designations = designations,
            )

        fun snap(boundCards: List<BoundCard>): GsmSnapshot =
            GsmSnapshot.forTest(
                boundCards = boundCards.associateBy { it.forgeCardId },
            )

        // Identity resolver mirrors instanceId = forgeCardId.value * 100 — easy to read in
        // assertions and won't collide with the test's hand-crafted Resolve ZT iids.
        val resolveIid = { fid: ForgeCardId -> InstanceId(fid.value * 100) }

        test("Prepared gain inserts immediately before the matching Resolve ZT") {
            val sourceFid = 7
            val sourceIid = sourceFid * 100
            val resolveZt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = InstanceId(sourceIid),
                    srcZoneId = 9, // Stack
                    destZoneId = 7, // Battlefield
                    category = "Resolve",
                )
            val annotations = mutableListOf<AnnotationInfo>(resolveZt)

            val prev = snap(emptyList())
            val cur =
                snap(
                    listOf(
                        bound(
                            sourceFid,
                            DesignationSet(prepared = PreparedRole.Source(copyForgeCardId = ForgeCardId(99))),
                        ),
                    ),
                )

            insertStateDesignationTransients(annotations, prev, cur, resolveIid)

            // Expect: gain inserted at index 0 (before the Resolve ZT now at 1).
            assertSoftly {
                annotations shouldHaveSize 2
                annotations[0].typeList shouldContain AnnotationType.GainDesignation
                annotations[0].affectorId shouldBe sourceIid
                annotations[0]
                    .detailsList
                    .first { it.key == DetailKeys.DESIGNATION_TYPE }
                    .getValueInt32(0) shouldBe AnnotationConstants.DESIGNATION_TYPE_PREPARED
                annotations[1] shouldBe resolveZt
            }
        }

        test("Prepared lose appends to the end") {
            val sourceFid = 11
            val annotations = mutableListOf<AnnotationInfo>()
            val prev =
                snap(
                    listOf(
                        bound(
                            sourceFid,
                            DesignationSet(prepared = PreparedRole.Source(copyForgeCardId = ForgeCardId(99))),
                        ),
                    ),
                )
            val cur = snap(listOf(bound(sourceFid, DesignationSet())))

            insertStateDesignationTransients(annotations, prev, cur, resolveIid)

            assertSoftly {
                annotations shouldHaveSize 1
                annotations[0].typeList shouldContain AnnotationType.LoseDesignation
                annotations[0]
                    .detailsList
                    .first { it.key == DetailKeys.DESIGNATION_TYPE }
                    .getValueInt32(0) shouldBe AnnotationConstants.DESIGNATION_TYPE_PREPARED
            }
        }

        test("Plotted gain and lose both append (no Resolve-ZT anchor)") {
            val plotFid = 13
            val annotations = mutableListOf<AnnotationInfo>()
            val prev = snap(listOf(bound(plotFid, DesignationSet())))
            val cur =
                snap(
                    listOf(
                        bound(plotFid, DesignationSet(plotted = PlottedRole.Plotted)),
                    ),
                )

            insertStateDesignationTransients(annotations, prev, cur, resolveIid)
            assertSoftly {
                annotations shouldHaveSize 1
                annotations[0].typeList shouldContain AnnotationType.GainDesignation
                annotations[0]
                    .detailsList
                    .first { it.key == DetailKeys.DESIGNATION_TYPE }
                    .getValueInt32(0) shouldBe AnnotationConstants.DESIGNATION_TYPE_PLOTTED
            }

            // And lose: cur strips Plotted.
            val annotations2 = mutableListOf<AnnotationInfo>()
            insertStateDesignationTransients(annotations2, cur, prev, resolveIid)
            assertSoftly {
                annotations2 shouldHaveSize 1
                annotations2[0].typeList shouldContain AnnotationType.LoseDesignation
            }
        }

        test("Foretold gain emits FaceDown + SuppressedPowerAndToughness pair, no lose") {
            val foretoldFid = 17
            val gainAnnotations = mutableListOf<AnnotationInfo>()
            val prev = snap(listOf(bound(foretoldFid, DesignationSet())))
            val cur = snap(listOf(bound(foretoldFid, DesignationSet(foretold = true))))

            insertStateDesignationTransients(gainAnnotations, prev, cur, resolveIid)
            gainAnnotations.map { it.typeList.first() } shouldContainExactly
                listOf(AnnotationType.FaceDown, AnnotationType.SuppressedPowerAndToughness)

            // Reverse direction: foretold flag clears. Foretold has FACE_DOWN_PAIR mode
            // which has no lose path.
            val loseAnnotations = mutableListOf<AnnotationInfo>()
            insertStateDesignationTransients(loseAnnotations, cur, prev, resolveIid)
            loseAnnotations.shouldBeEmpty()
        }

        test("Table inventory pins the three current rows in order") {
            CardStateDesignations.all.map { it.kind } shouldContainExactly
                listOf(DesignationKind.PREPARED, DesignationKind.PLOTTED, DesignationKind.FORETOLD)
        }

        test("Each row's designationType matches the AnnotationConstants pin") {
            assertSoftly {
                CardStateDesignations.Prepared.designationType shouldBe AnnotationConstants.DESIGNATION_TYPE_PREPARED
                CardStateDesignations.Plotted.designationType shouldBe AnnotationConstants.DESIGNATION_TYPE_PLOTTED
                CardStateDesignations.Foretold.designationType shouldBe null
            }
        }
    })
