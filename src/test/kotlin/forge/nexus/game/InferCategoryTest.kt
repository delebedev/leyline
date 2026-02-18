package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo

/**
 * Unit tests for [StateMapper.inferCategory] — the logic that maps
 * (srcZone, destZone) pairs to annotation categories.
 *
 * Each category drives a different annotation sequence in buildFromGame:
 *   PlayLand → ObjectIdChanged + ZoneTransfer + UserActionTaken
 *   CastSpell → ObjectIdChanged + ZoneTransfer + mana cycle + UserActionTaken
 *   Resolve → ResolutionStart + ResolutionComplete + ZoneTransfer
 */
@Test
class InferCategoryTest {

    private fun dummyObj(): GameObjectInfo = GameObjectInfo.getDefaultInstance()

    @Test
    fun handToBattlefieldIsPlayLand() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.BATTLEFIELD), TransferCategory.PlayLand)
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD), TransferCategory.PlayLand)
    }

    @Test
    fun handToStackIsCastSpell() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.STACK), TransferCategory.CastSpell)
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.P2_HAND, ZoneIds.STACK), TransferCategory.CastSpell)
    }

    @Test
    fun stackToBattlefieldIsResolve() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.BATTLEFIELD), TransferCategory.Resolve)
    }

    @Test
    fun battlefieldToGraveyardIsDestroy() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P1_GRAVEYARD), TransferCategory.Destroy)
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.P2_GRAVEYARD), TransferCategory.Destroy)
    }

    @Test
    fun battlefieldToExileIsExile() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.EXILE), TransferCategory.Exile)
    }

    @Test
    fun handToUnknownZoneIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.P1_HAND, ZoneIds.EXILE), TransferCategory.ZoneTransfer)
    }

    @Test
    fun battlefieldToStackIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.BATTLEFIELD, ZoneIds.STACK), TransferCategory.ZoneTransfer)
    }

    @Test
    fun unknownZonePairIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.EXILE, ZoneIds.P1_GRAVEYARD), TransferCategory.ZoneTransfer)
        assertEquals(StateMapper.inferCategory(dummyObj(), ZoneIds.STACK, ZoneIds.P1_GRAVEYARD), TransferCategory.ZoneTransfer)
    }
}
