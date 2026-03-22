package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.GameBootstrap
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Combat/zone-category annotation pipeline tests — destroy, sacrifice, bounce,
 * exile, discard, draw, mill, surveil (transfer), countered, return, search, put.
 */
class CombatZoneAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("destroyProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Destroy,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[1].detailString("category") shouldBe "Destroy"
            persistent.shouldBeEmpty()
        }

        test("sacrificeProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Sacrifice"
        }

        test("Sacrifice with mana payment emits full mana-ability bracket") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104, // Treasure token
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 100,
                        manaAbilityInstanceId = 200100,
                        color = 8, // Red
                        abilityGrpId = 183,
                        spellInstanceId = 300,
                    ),
                ),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 7
                annotations[0].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
                annotations[1].typeList.first() shouldBe AnnotationType.TappedUntappedPermanent
                annotations[2].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[3].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[4].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[5].typeList.first() shouldBe AnnotationType.ManaPaid
                annotations[6].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
            }

            // ManaPaid: affectorId = land (origId=100), affectedIds contains spellInstanceId
            annotations[5].affectorId shouldBe 100
            annotations[5].affectedIdsList shouldContain 300
        }

        test("Sacrifice without mana payment emits standard annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104,
                ownerSeatId = 1,
                manaPayments = emptyList(),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
        }

        test("Sacrifice with mana payment has correct UserActionTaken fields") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104,
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 100,
                        manaAbilityInstanceId = 200100,
                        color = 8,
                        abilityGrpId = 183,
                        spellInstanceId = 300,
                    ),
                ),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            val uat = annotations[4]
            assertSoftly {
                uat.typeList.first() shouldBe AnnotationType.UserActionTaken
                uat.detailInt("actionType") shouldBe 4
                uat.detailInt("abilityGrpId") shouldBe 183
            }
        }

        test("bounceProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Bounce,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Bounce"
        }

        test("exileProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Exile,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.EXILE,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Exile"
        }

        test("discardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Discard,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Discard"
        }

        test("drawProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Draw,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Draw"
        }

        test("millProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Mill,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Mill"
        }

        test("surveilProducesAnnotationsWithAffectorId") {
            val abilityInstanceId = 500
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Surveil,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
                affectorId = abilityInstanceId,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.size shouldBe 2

            // ObjectIdChanged carries affectorId
            val oidChanged = annotations[0]
            assertSoftly {
                oidChanged.typeList.first() shouldBe AnnotationType.ObjectIdChanged
                oidChanged.affectorId shouldBe abilityInstanceId
                oidChanged.affectedIdsList shouldContain 100
            }

            // ZoneTransfer carries affectorId and category
            val zt = annotations[1]
            assertSoftly {
                zt.typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                zt.affectorId shouldBe abilityInstanceId
                zt.affectedIdsList shouldContain 200
                zt.detailString("category") shouldBe "Surveil"
            }
        }

        test("surveilWithoutAffectorIdHasZeroAffector") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Surveil,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
                // no affectorId
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            // Without affectorId, annotations should still work but have 0 affector
            annotations[0].affectorId shouldBe 0
            annotations[1].affectorId shouldBe 0
        }

        test("counteredProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Countered,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Countered"
        }

        // --- annotationsForTransfer: Return ---

        test("returnFromGraveyardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[1].detailString("category") shouldBe "Return"
            persistent.size shouldBe 1
        }

        test("returnToHandNoPersistent") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.last().detailString("category") shouldBe "Return"
            persistent.shouldBeEmpty()
        }

        // --- annotationsForTransfer: Search ---

        test("searchProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Search,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[1].detailString("category") shouldBe "Search"
            persistent.size shouldBe 1
        }

        // --- annotationsForTransfer: Put ---

        test("putProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Put,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[1].detailString("category") shouldBe "Put"
            persistent.shouldBeEmpty()
        }
    })
