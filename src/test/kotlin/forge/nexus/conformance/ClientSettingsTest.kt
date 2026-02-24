package forge.nexus.conformance

import forge.game.phase.PhaseType
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration tests for client SetSettingsReq → PhaseStopProfile wiring.
 *
 * Verifies that toggling stops in the client settings message updates the
 * [PhaseStopProfile] on [GameBridge], which in turn controls where the
 * engine stops during the auto-pass loop.
 */
@Test(groups = ["integration"])
class ClientSettingsTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    @Test(description = "Enabling Upkeep stop via Team scope updates the profile")
    fun enableUpkeepStopViaTeamScope() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val profile = harness.bridge.phaseStopProfile!!
        val humanId = harness.bridge.getPlayer(1)!!.id

        // Default: Upkeep is NOT enabled for human
        assertFalse(profile.isEnabled(humanId, PhaseType.UPKEEP), "Upkeep should be off by default")

        // Send settings with Upkeep = Set for Team scope
        sendSettings(
            stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set),
        )

        assertTrue(profile.isEnabled(humanId, PhaseType.UPKEEP), "Upkeep should be enabled after settings")
    }

    @Test(description = "Disabling Main1 stop via Team scope updates the profile")
    fun disableMain1StopViaTeamScope() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val profile = harness.bridge.phaseStopProfile!!
        val humanId = harness.bridge.getPlayer(1)!!.id

        // Default: Main1 IS enabled for human
        assertTrue(profile.isEnabled(humanId, PhaseType.MAIN1), "Main1 should be on by default")

        // Send settings with PrecombatMainPhase = Clear for Team scope
        sendSettings(
            stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
        )

        assertFalse(profile.isEnabled(humanId, PhaseType.MAIN1), "Main1 should be disabled after settings")
    }

    @Test(description = "Multiple stops can be toggled in a single settings message")
    fun multipleStopChanges() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val profile = harness.bridge.phaseStopProfile!!
        val humanId = harness.bridge.getPlayer(1)!!.id

        // Enable Draw, disable Main2
        sendSettings(
            stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
            stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
        )

        assertTrue(profile.isEnabled(humanId, PhaseType.DRAW), "Draw should be enabled")
        assertFalse(profile.isEnabled(humanId, PhaseType.MAIN2), "Main2 should be disabled")
        // Unchanged defaults still hold
        assertTrue(profile.isEnabled(humanId, PhaseType.MAIN1), "Main1 should still be on (untouched)")
    }

    @Test(description = "Opponents scope stops don't affect the human player's profile (v1)")
    fun opponentsScopeDoesNotAffectHuman() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val profile = harness.bridge.phaseStopProfile!!
        val humanId = harness.bridge.getPlayer(1)!!.id

        val before = profile.getEnabled(humanId)

        // Send Opponents-only stop change
        sendSettings(
            stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set),
        )

        val after = profile.getEnabled(humanId)
        assertTrue(before == after, "Opponents scope should not change human profile (v1)")
    }

    @Test(description = "AnyPlayer scope applies to Team query (human profile)")
    fun anyPlayerScopeAppliesToHuman() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val profile = harness.bridge.phaseStopProfile!!
        val humanId = harness.bridge.getPlayer(1)!!.id

        assertFalse(profile.isEnabled(humanId, PhaseType.END_OF_TURN), "EndOfTurn should be off for human by default")

        sendSettings(
            stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set),
        )

        assertTrue(profile.isEnabled(humanId, PhaseType.END_OF_TURN), "AnyPlayer Set should enable for human")
    }

    @Test(description = "Settings response is echoed back as raw message")
    fun settingsResponseEchoed() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        sendSettings(
            stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
        )
        harness.drainSink()

        // SettingsResp goes via sendRaw → GreToClientEvent wrapper
        assertTrue(harness.allRawMessages.isNotEmpty(), "Should receive SettingsResp raw message")
        val last = harness.allRawMessages.last()
        assertTrue(last.hasGreToClientEvent(), "Raw message should be a GreToClientEvent")
        val hasSettingsResp = last.greToClientEvent.greToClientMessagesList
            .any { it.type == GREMessageType.SetSettingsResp_695e }
        assertTrue(hasSettingsResp, "Should contain a SetSettingsResp message")
    }

    // --- Helpers ---

    private fun sendSettings(vararg stops: Stop) {
        val msg = clientMessage(ClientMessageType.SetSettingsReq_097b) {
            setSetSettingsReq(
                SetSettingsReq.newBuilder().setSettings(
                    SettingsMessage.newBuilder().addAllStops(stops.toList()),
                ),
            )
        }
        harness.session.onSettings(msg)
    }

    private fun stop(type: StopType, scope: SettingScope, status: SettingStatus): Stop =
        Stop.newBuilder()
            .setStopType(type)
            .setAppliesTo(scope)
            .setStatus(status)
            .build()
}
