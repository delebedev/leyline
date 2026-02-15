package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

@Test(groups = ["integration"])
class MatchFlowHarnessTest {

    private var harness: MatchFlowHarness? = null

    @AfterMethod
    fun tearDown() {
        harness?.shutdown()
        harness = null
    }

    @Test(description = "Harness can start game and reach Main1 with valid accumulated state")
    fun startGameReachesMain1() {
        harness = MatchFlowHarness(seed = 42L)
        harness!!.connectAndKeep()

        val acc = harness!!.accumulator
        assertTrue(acc.objects.isNotEmpty(), "Should have accumulated game objects")
        assertNotNull(acc.actions, "Should have actions available")

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing after game start: $missing")

        assertEquals(harness!!.phase(), "MAIN1", "Should be at Main1")
    }
}
