package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo

/**
 * Unit tests for [StateMapper.inferCategory] — the logic that maps
 * (srcZone, destZone) pairs to annotation category strings.
 *
 * Each category drives a different annotation sequence in buildFromGame:
 *   PlayLand → ObjectIdChanged + ZoneTransfer + UserActionTaken
 *   CastSpell → ObjectIdChanged + ZoneTransfer + mana cycle + UserActionTaken
 *   Resolve → ResolutionStart + ResolutionComplete + ZoneTransfer
 */
@Test
class InferCategoryTest {

    private companion object {
        const val ZONE_STACK = 27
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_EXILE = 29
        const val ZONE_P1_HAND = 31
        const val ZONE_P1_GRAVEYARD = 33
        const val ZONE_P2_HAND = 35
        const val ZONE_P2_GRAVEYARD = 37
    }

    private fun dummyObj(): GameObjectInfo = GameObjectInfo.getDefaultInstance()

    @Test
    fun handToBattlefieldIsPlayLand() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_P1_HAND, ZONE_BATTLEFIELD), "PlayLand")
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_P2_HAND, ZONE_BATTLEFIELD), "PlayLand")
    }

    @Test
    fun handToStackIsCastSpell() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_P1_HAND, ZONE_STACK), "CastSpell")
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_P2_HAND, ZONE_STACK), "CastSpell")
    }

    @Test
    fun stackToBattlefieldIsResolve() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_STACK, ZONE_BATTLEFIELD), "Resolve")
    }

    @Test
    fun battlefieldToGraveyardIsDestroy() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_BATTLEFIELD, ZONE_P1_GRAVEYARD), "Destroy")
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_BATTLEFIELD, ZONE_P2_GRAVEYARD), "Destroy")
    }

    @Test
    fun battlefieldToExileIsExile() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_BATTLEFIELD, ZONE_EXILE), "Exile")
    }

    @Test
    fun handToUnknownZoneIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_P1_HAND, ZONE_EXILE), "ZoneTransfer")
    }

    @Test
    fun battlefieldToStackIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_BATTLEFIELD, ZONE_STACK), "ZoneTransfer")
    }

    @Test
    fun unknownZonePairIsZoneTransfer() {
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_EXILE, ZONE_P1_GRAVEYARD), "ZoneTransfer")
        assertEquals(StateMapper.inferCategory(dummyObj(), ZONE_STACK, ZONE_P1_GRAVEYARD), "ZoneTransfer")
    }
}
