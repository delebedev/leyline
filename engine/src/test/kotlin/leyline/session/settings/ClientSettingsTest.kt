package leyline.session.settings

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.clientMessage
import leyline.testkit.stop
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration tests for client SetSettingsReq → runtime-owned phase-stop wiring.
 *
 * Verifies that toggling stops in the client settings message updates the
 * priority runtime, which in turn controls where the engine stops during the
 * runtime continuation.
 */
class ClientSettingsTest :
    SessionTest({

        fun MatchFlowHarness.sendSettings(vararg stops: Stop) {
            val msg =
                clientMessage(ClientMessageType.SetSettingsReq_097b) {
                    setSetSettingsReq(
                        SetSettingsReq.newBuilder().setSettings(
                            SettingsMessage.newBuilder().addAllStops(stops.toList()),
                        ),
                    )
                }
            send(msg)
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

        session("enabling Upkeep stop via Team scope updates the runtime") {
            val policy = bridge.priorityPolicy
            val humanId = human.id

            // Default: Upkeep is NOT enabled for human
            policy.isPhaseStopped(humanId, PhaseType.UPKEEP).shouldBeFalse()

            // Send settings with Upkeep = Set for Team scope
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))

            assertSoftly {
                policy.isPhaseStopped(humanId, PhaseType.UPKEEP).shouldBeTrue()
                policy.enabledPhaseStops(humanId) shouldBe
                    setOf(
                        PhaseType.UPKEEP,
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                    )
            }
        }

        session("disabling Main1 stop via Team scope updates the runtime") {
            val policy = bridge.priorityPolicy
            val humanId = human.id

            // Default: Main1 IS enabled for human
            policy.isPhaseStopped(humanId, PhaseType.MAIN1).shouldBeTrue()

            // Send settings with PrecombatMainPhase = Clear for Team scope
            sendSettings(stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe))

            assertSoftly {
                policy.isPhaseStopped(humanId, PhaseType.MAIN1).shouldBeFalse()
                policy.enabledPhaseStops(humanId) shouldBe
                    setOf(
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                    )
            }
        }

        session("multiple stops can be toggled in a single settings message") {
            val policy = bridge.priorityPolicy
            val humanId = human.id

            // Enable Draw, disable Main2
            sendSettings(
                stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
                stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
            )

            assertSoftly {
                policy.isPhaseStopped(humanId, PhaseType.DRAW).shouldBeTrue()
                policy.isPhaseStopped(humanId, PhaseType.MAIN2).shouldBeFalse()
                policy.isPhaseStopped(humanId, PhaseType.MAIN1).shouldBeTrue()
                policy.enabledPhaseStops(humanId) shouldBe
                    setOf(
                        PhaseType.DRAW,
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                    )
            }
        }

        session("opponents scope does not affect human") {
            val policy = bridge.priorityPolicy
            val humanId = human.id

            val before = policy.enabledPhaseStops(humanId)

            // Send Opponents-only stop change
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))

            val after = policy.enabledPhaseStops(humanId)
            after shouldBe before
        }

        session("AnyPlayer scope applies to human") {
            val policy = bridge.priorityPolicy
            val humanId = human.id

            policy.isPhaseStopped(humanId, PhaseType.END_OF_TURN).shouldBeFalse()

            sendSettings(stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set))

            assertSoftly {
                policy.isPhaseStopped(humanId, PhaseType.END_OF_TURN).shouldBeTrue()
                policy.enabledPhaseStops(humanId) shouldBe
                    setOf(
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                        PhaseType.END_OF_TURN,
                    )
            }
        }

        session("settings response is echoed back as raw message") {
            sendSettings(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
            drainSink()

            val last =
                allRawMessages.single { message ->
                    message.hasGreToClientEvent() &&
                        message.greToClientEvent.greToClientMessagesList
                            .singleOrNull()
                            ?.type ==
                        GREMessageType.SetSettingsResp_695e
                }
            assertSoftly {
                last.hasGreToClientEvent().shouldBeTrue()
                last.greToClientEvent.greToClientMessagesList.map { it.type } shouldBe listOf(GREMessageType.SetSettingsResp_695e)
            }
        }
    })
