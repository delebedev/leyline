package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** Unit tests for [InstanceIdRegistry]. */
@Test(groups = ["unit"])
class InstanceIdRegistryTest {

    @Test
    fun resetAllReturnsOldIdsAndClearsState() {
        val reg = InstanceIdRegistry(startId = 100)
        val id1 = reg.getOrAlloc(1)
        val id2 = reg.getOrAlloc(2)
        val id3 = reg.getOrAlloc(3)

        val deleted = reg.resetAll()

        assertEquals(deleted.toSet(), setOf(id1, id2, id3), "resetAll should return all old instanceIds")
        // After reset, same forgeCardIds get fresh IDs
        val fresh1 = reg.getOrAlloc(1)
        val fresh2 = reg.getOrAlloc(2)
        assertTrue(fresh1 != id1, "Post-reset ID should differ from pre-reset")
        assertTrue(fresh2 != id2, "Post-reset ID should differ from pre-reset")
    }

    @Test
    fun resetAllClearsReverseMap() {
        val reg = InstanceIdRegistry(startId = 100)
        val oldId = reg.getOrAlloc(42)

        reg.resetAll()

        // Old reverse lookup should return null
        assertEquals(reg.getForgeCardId(oldId), null, "Old instanceId should not resolve after reset")
        // New allocation should be resolvable
        val newId = reg.getOrAlloc(42)
        assertNotNull(reg.getForgeCardId(newId), "New instanceId should resolve")
    }
}
