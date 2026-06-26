package leyline.session.settings

import forge.game.phase.PhaseType
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.clientMessage
import leyline.testkit.stop
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration tests for client SetSettingsReq → PhaseStopProfile wiring.
 *
 * Verifies that toggling stops in the client settings message updates the
 * [PhaseStopProfile] on [GameBridge], which in turn controls where the
 * engine stops during the auto-pass loop.
 */
class ClientSettingsTest :
    SessionTest({

        fun sendSettings(vararg stops: Stop) {
            val msg =
                clientMessage(ClientMessageType.SetSettingsReq_097b) {
                    setSetSettingsReq(
                        SetSettingsReq.newBuilder().setSettings(
                            SettingsMessage.newBuilder().addAllStops(stops.toList()),
                        ),
                    )
                }
            harness.session.onSettings(msg)
        }

        fun stop(
            type: StopType,
            scope: SettingScope,
            status: SettingStatus,
        ): Stop =
            Stop
                .newBuilder()
                .setStopType(type)
                .setAppliesTo(scope)
                .setStatus(status)
                .build()

        test("enabling Upkeep stop via Team scope updates the profile") {
            startGame()

            val profile = harness.bridge.phaseStopProfile!!
            val humanId = human.id

            // Default: Upkeep is NOT enabled for human
            profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeFalse()

            // Send settings with Upkeep = Set for Team scope
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))

            profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeTrue()
        }

        test("disabling Main1 stop via Team scope updates the profile") {
            startGame()

            val profile = harness.bridge.phaseStopProfile!!
            val humanId = human.id

            // Default: Main1 IS enabled for human
            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()

            // Send settings with PrecombatMainPhase = Clear for Team scope
            sendSettings(stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe))

            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeFalse()
        }

        test("multiple stops can be toggled in a single settings message") {
            startGame()

            val profile = harness.bridge.phaseStopProfile!!
            val humanId = human.id

            // Enable Draw, disable Main2
            sendSettings(
                stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
                stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
            )

            profile.isEnabled(humanId, PhaseType.DRAW).shouldBeTrue()
            profile.isEnabled(humanId, PhaseType.MAIN2).shouldBeFalse()
            // Unchanged defaults still hold
            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()
        }

        test("opponents scope does not affect human") {
            startGame()

            val profile = harness.bridge.phaseStopProfile!!
            val humanId = human.id

            val before = profile.getEnabled(humanId)

            // Send Opponents-only stop change
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))

            val after = profile.getEnabled(humanId)
            after shouldBe before
        }

        test("AnyPlayer scope applies to human") {
            startGame()

            val profile = harness.bridge.phaseStopProfile!!
            val humanId = human.id

            profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeFalse()

            sendSettings(stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set))

            profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeTrue()
        }

        test("settings response is echoed back as raw message") {
            startGame()

            sendSettings(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
            harness.drainSink()

            // SettingsResp goes via sendRaw → GreToClientEvent wrapper
            harness.allRawMessages.shouldNotBeEmpty()
            val last = harness.allRawMessages.last()
            last.hasGreToClientEvent().shouldBeTrue()
            val hasSettingsResp =
                last.greToClientEvent.greToClientMessagesList
                    .any { it.type == GREMessageType.SetSettingsResp_695e }
            hasSettingsResp.shouldBeTrue()
        }
    })
