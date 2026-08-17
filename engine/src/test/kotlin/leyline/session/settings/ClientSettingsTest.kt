package leyline.session.settings

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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

        session("enabling Upkeep stop via Team scope updates the profile", validating = true) {
            val profile = bridge.phaseStopProfile!!
            val humanId = human.id

            // Default: Upkeep is NOT enabled for human
            profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeFalse()

            // Send settings with Upkeep = Set for Team scope
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))

            assertSoftly {
                profile.isEnabled(humanId, PhaseType.UPKEEP).shouldBeTrue()
                profile.getEnabled(humanId) shouldBe
                    setOf(
                        PhaseType.UPKEEP,
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                    )
            }
        }

        session("disabling Main1 stop via Team scope updates the profile", validating = true) {
            val profile = bridge.phaseStopProfile!!
            val humanId = human.id

            // Default: Main1 IS enabled for human
            profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()

            // Send settings with PrecombatMainPhase = Clear for Team scope
            sendSettings(stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe))

            assertSoftly {
                profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeFalse()
                profile.getEnabled(humanId) shouldBe
                    setOf(
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                    )
            }
        }

        session("multiple stops can be toggled in a single settings message", validating = true) {
            val profile = bridge.phaseStopProfile!!
            val humanId = human.id

            // Enable Draw, disable Main2
            sendSettings(
                stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
                stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
            )

            assertSoftly {
                profile.isEnabled(humanId, PhaseType.DRAW).shouldBeTrue()
                profile.isEnabled(humanId, PhaseType.MAIN2).shouldBeFalse()
                profile.isEnabled(humanId, PhaseType.MAIN1).shouldBeTrue()
                profile.getEnabled(humanId) shouldBe
                    setOf(
                        PhaseType.DRAW,
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                    )
            }
        }

        session("opponents scope does not affect human", validating = true) {
            val profile = bridge.phaseStopProfile!!
            val humanId = human.id

            val before = profile.getEnabled(humanId)

            // Send Opponents-only stop change
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))

            val after = profile.getEnabled(humanId)
            after shouldBe before
        }

        session("AnyPlayer scope applies to human", validating = true) {
            val profile = bridge.phaseStopProfile!!
            val humanId = human.id

            profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeFalse()

            sendSettings(stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set))

            assertSoftly {
                profile.isEnabled(humanId, PhaseType.END_OF_TURN).shouldBeTrue()
                profile.getEnabled(humanId) shouldBe
                    setOf(
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                        PhaseType.END_OF_TURN,
                    )
            }
        }

        session("settings response is echoed back as raw message", validating = true) {
            sendSettings(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
            drainSink()

            val last = allRawMessages.single()
            assertSoftly {
                last.hasGreToClientEvent().shouldBeTrue()
                last.greToClientEvent.greToClientMessagesList.map { it.type } shouldBe listOf(GREMessageType.SetSettingsResp_695e)
            }
        }
    })
