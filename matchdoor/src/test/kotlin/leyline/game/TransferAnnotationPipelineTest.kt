package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.GameBootstrap
import leyline.conformance.detailInt
import leyline.conformance.detailUint
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Transfer-stage annotation pipeline tests — PlayLand, CastSpell, Resolve,
 * generic ZoneTransfer, and persistent annotation generation for transfers.
 */
class TransferAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        // --- annotationsForTransfer: PlayLand ---

        test("playLandProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken
            }

            // UserActionTaken should have actionType=3 (Play)
            annotations[2].detailInt("actionType") shouldBe 3
        }

        test("playLandHasCorrectIds") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ObjectIdChanged should reference origId in affectedIds
            annotations[0].affectedIdsList shouldContain 100
            // ZoneTransfer should reference newId
            annotations[1].affectedIdsList shouldContain 200
        }

        test("playLandProducesPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        // --- annotationsForTransfer: CastSpell ---

        test("castSpell with one mana payment produces 8 annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 300,
                        manaAbilityInstanceId = 400,
                        color = 2,
                        abilityGrpId = 1002,
                    ),
                ),
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 8
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
                annotations[3].typeList.first() shouldBe AnnotationType.TappedUntappedPermanent
                annotations[4].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[5].typeList.first() shouldBe AnnotationType.ManaPaid
                annotations[6].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
                annotations[7].typeList.first() shouldBe AnnotationType.UserActionTaken
            }

            // AIC details
            assertSoftly {
                annotations[2].affectorId shouldBe 300
                annotations[2].affectedIdsList shouldContain 400
                annotations[2].detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
            }

            // TUP
            annotations[3].affectorId shouldBe 400
            annotations[3].affectedIdsList shouldContain 300

            // UAT mana
            assertSoftly {
                annotations[4].detailInt("actionType") shouldBe 4
                annotations[4].detailInt("abilityGrpId") shouldBe 1002
                annotations[4].affectedIdsList shouldContain 400
            }

            // ManaPaid
            assertSoftly {
                annotations[5].affectorId shouldBe 300
                annotations[5].affectedIdsList shouldContain 200
                annotations[5].detailInt("color") shouldBe 2
            }

            // AID
            annotations[6].affectorId shouldBe 300
            annotations[6].affectedIdsList shouldContain 400

            // UAT cast
            annotations[7].detailInt("actionType") shouldBe 1
            annotations[7].affectedIdsList shouldContain 200

            // Stack gets EnteredZoneThisTurn (recording confirms)
            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        test("castSpell with zero mana payments produces 3 annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
                manaPayments = emptyList(),
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[2].detailInt("actionType") shouldBe 1
            }

            persistent.size shouldBe 1
        }

        test("castSpellUserActionIsCast") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.last().detailInt("actionType") shouldBe 1
        }

        // --- annotationsForTransfer: Resolve ---

        test("resolveProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ResolutionStart
                annotations[1].typeList.first() shouldBe AnnotationType.ResolutionComplete
                annotations[2].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            }

            // Lands on battlefield — persistent annotation
            persistent.size shouldBe 1
        }

        test("resolveZoneTransferHasActingSeat") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 2)

            // Resolve ZoneTransfer should carry actingSeat as affectorId
            annotations[2].affectorId shouldBe 2
        }

        test("resolveUsesGrpId") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations[0].detailUint("grpid") shouldBe 67890
        }

        // --- Edge cases ---

        test("genericZoneTransferProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.ZoneTransfer,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ZoneTransfer category produces ObjectIdChanged (when origId != newId) + ZoneTransfer
            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            persistent.shouldBeEmpty()
        }

        test("castSpellToStackGetsPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
            persistent[0].affectorId shouldBe ZoneIds.STACK
        }

        test("resolveToGraveyardNoPersistentAnnotation") {
            // Spell resolves but goes to graveyard (instant/sorcery)
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 3
            persistent.shouldBeEmpty()
        }
    })
