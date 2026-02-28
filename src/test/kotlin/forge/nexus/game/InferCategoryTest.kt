package forge.nexus.game

import forge.nexus.game.mapper.ZoneIds
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
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
@Test(groups = ["unit"])
class InferCategoryTest {

    private fun dummyObj(): GameObjectInfo = GameObjectInfo.getDefaultInstance()

    @Test
    fun handToBattlefieldIsPlayLand() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.BATTLEFIELD), TransferCategory.PlayLand)
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD), TransferCategory.PlayLand)
    }

    @Test
    fun handToStackIsCastSpell() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.STACK), TransferCategory.CastSpell)
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.STACK), TransferCategory.CastSpell)
    }

    @Test
    fun stackToBattlefieldIsResolve() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.BATTLEFIELD), TransferCategory.Resolve)
    }

    @Test
    fun battlefieldToGraveyardIsDestroy() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P1_GRAVEYARD), TransferCategory.Destroy)
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P2_GRAVEYARD), TransferCategory.Destroy)
    }

    @Test
    fun battlefieldToExileIsExile() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.EXILE), TransferCategory.Exile)
    }

    @Test
    fun handToUnknownZoneIsZoneTransfer() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.EXILE), TransferCategory.ZoneTransfer)
    }

    @Test
    fun battlefieldToStackIsZoneTransfer() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.STACK), TransferCategory.ZoneTransfer)
    }

    @Test
    fun unknownZonePairIsZoneTransfer() {
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.EXILE, ZoneIds.P1_GRAVEYARD), TransferCategory.ZoneTransfer)
        assertEquals(AnnotationPipeline.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.P1_GRAVEYARD), TransferCategory.ZoneTransfer)
    }
}
