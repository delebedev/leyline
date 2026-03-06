package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo

/**
 * Unit tests for [AnnotationPipeline.inferCategory] — the logic that maps
 * (srcZone, destZone) pairs to annotation categories.
 *
 * Each category drives a different annotation sequence in buildFromGame:
 *   PlayLand → ObjectIdChanged + ZoneTransfer + UserActionTaken
 *   CastSpell → ObjectIdChanged + ZoneTransfer + mana cycle + UserActionTaken
 *   Resolve → ResolutionStart + ResolutionComplete + ZoneTransfer
 */
class InferCategoryTest :
    FunSpec({

        tags(UnitTag)

        fun dummyObj(): GameObjectInfo = GameObjectInfo.getDefaultInstance()

        test("handToBattlefieldIsPlayLand") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.BATTLEFIELD) shouldBe TransferCategory.PlayLand
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD) shouldBe TransferCategory.PlayLand
        }

        test("handToStackIsCastSpell") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.STACK) shouldBe TransferCategory.CastSpell
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.STACK) shouldBe TransferCategory.CastSpell
        }

        test("stackToBattlefieldIsResolve") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.BATTLEFIELD) shouldBe TransferCategory.Resolve
        }

        test("battlefieldToGraveyardIsDestroy") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P1_GRAVEYARD) shouldBe TransferCategory.Destroy
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P2_GRAVEYARD) shouldBe TransferCategory.Destroy
        }

        test("battlefieldToExileIsExile") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.EXILE) shouldBe TransferCategory.Exile
        }

        test("handToUnknownZoneIsZoneTransfer") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.EXILE) shouldBe TransferCategory.ZoneTransfer
        }

        test("battlefieldToStackIsZoneTransfer") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.STACK) shouldBe TransferCategory.ZoneTransfer
        }

        test("unknownZonePairIsZoneTransfer") {
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.EXILE, ZoneIds.P1_GRAVEYARD) shouldBe TransferCategory.ZoneTransfer
            AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.P1_GRAVEYARD) shouldBe TransferCategory.ZoneTransfer
        }
    })
