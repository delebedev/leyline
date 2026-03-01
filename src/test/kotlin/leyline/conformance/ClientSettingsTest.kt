package leyline.conformance

import forge.game.phase.PhaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration tests for client SetSettingsReq → PhaseStopProfile wiring.
 *
 * Verifies that toggling stops in the client settings message updates the
 * [PhaseStopProfile] on [GameBridge], which in turn controls where the
 * engine stops during the auto-pass loop.
 */
class ClientSettingsTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun sendSettings(h: MatchFlowHarness, vararg stops: Stop) {
            val msg = clientMessage(ClientMessageType.SetSettingsReq_097b) {
                setSetSettingsReq(
                    SetSettingsReq.newBuilder().setSettings(
                        SettingsMessage.newBuilder().addAllStops(stops.toList()),
                    ),
                )
            }
            h.session.onSettings(msg)
        }

        fun stop(type: StopType, scope: SettingScope, status: SettingStatus): Stop =
            Stop.newBuilder()
                .setStopType(type)
                .setAppliesTo(scope)
                .setStatus(status)
                .build()

        test("enabling Upkeep stop via Team scope updates the profile") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val profile = h.bridge.phaseStopProfile!!
            val humanId = h.bridge.getPlayer(1)!!.id

            // Default: Upkeep is NOT enabled for human
            profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeFalse()

            // Send settings with Upkeep = Set for Team scope
            sendSettings(h, stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))

            profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeTrue()
        }

        test("disabling Main1 stop via Team scope updates the profile") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val profile = h.bridge.phaseStopProfile!!
            val humanId = h.bridge.getPlayer(1)!!.id

            // Default: Main1 IS enabled for human
            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()

            // Send settings with PrecombatMainPhase = Clear for Team scope
            sendSettings(h, stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe))

            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeFalse()
        }

        test("multiple stops can be toggled in a single settings message") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val profile = h.bridge.phaseStopProfile!!
            val humanId = h.bridge.getPlayer(1)!!.id

            // Enable Draw, disable Main2
            sendSettings(
                h,
                stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
                stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
            )

            profile.isEnabled(humanId, PhaseType.DRAW).shouldBeTrue()
            profile.isEnabled(humanId, PhaseType.MAIN2).shouldBeFalse()
            // Unchanged defaults still hold
            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()
        }

        test("opponents scope does not affect human") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val profile = h.bridge.phaseStopProfile!!
            val humanId = h.bridge.getPlayer(1)!!.id

            val before = profile.getEnabled(humanId)

            // Send Opponents-only stop change
            sendSettings(h, stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))

            val after = profile.getEnabled(humanId)
            (before == after).shouldBeTrue()
        }

        test("AnyPlayer scope applies to human") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val profile = h.bridge.phaseStopProfile!!
            val humanId = h.bridge.getPlayer(1)!!.id

            profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeFalse()

            sendSettings(h, stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set))

            profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeTrue()
        }

        test("settings response is echoed back as raw message") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            sendSettings(h, stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
            h.drainSink()

            // SettingsResp goes via sendRaw → GreToClientEvent wrapper
            h.allRawMessages.shouldNotBeEmpty()
            val last = h.allRawMessages.last()
            last.hasGreToClientEvent().shouldBeTrue()
            val hasSettingsResp = last.greToClientEvent.greToClientMessagesList
                .any { it.type == GREMessageType.SetSettingsResp_695e }
            hasSettingsResp.shouldBeTrue()
        }
    })
