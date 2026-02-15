package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["integration", "conformance"])
class AccumulatorInvariantTest : ConformanceTestBase() {

    @Test(description = "After game-start bundle, all action instanceIds exist in accumulated objects")
    fun gameStartActionIdsExistInObjects() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing from objects after game-start: $missing")
    }

    // BUG: Library zones (32, 36) reference instanceIds not sent as GameObjectInfo.
    // game-start bundle emits zones with objectInstanceIds for library cards but never
    // includes the corresponding GameObjectInfo entries. ~106 missing refs per game.
    @Test(
        description = "After game-start, zone object references are valid",
        expectedExceptions = [AssertionError::class],
    )
    fun gameStartZoneRefsValid() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        val missing = acc.zoneObjectsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Zone references to missing objects: $missing")
    }

    @Test(description = "game-start bundle has monotonically increasing gsId")
    fun gameStartGsIdMonotonic() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        // Verify gsIds are strictly increasing
        for (i in 1 until acc.gsIdHistory.size) {
            assertTrue(
                acc.gsIdHistory[i] > acc.gsIdHistory[i - 1],
                "gsId should increase: ${acc.gsIdHistory[i - 1]} -> ${acc.gsIdHistory[i]} at index $i",
            )
        }
    }
}
