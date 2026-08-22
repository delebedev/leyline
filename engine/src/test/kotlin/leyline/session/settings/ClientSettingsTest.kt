package leyline.session.settings

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import wotc.mtgo.gre.external.messaging.Messages.*

/** SetSettingsReq is a semantic intent and updates the immutable stop profile. */
class ClientSettingsTest :
    SessionTest({
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

        session("enabling Upkeep stop via Team scope updates the profile") {
            enabledStops() shouldNotContain "UPKEEP"
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))
            enabledStops() shouldBe setOf("UPKEEP", "MAIN1", "COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS", "MAIN2")
        }

        session("disabling Main1 stop via Team scope updates the profile") {
            enabledStops() shouldContain "MAIN1"
            sendSettings(stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe))
            enabledStops() shouldBe setOf("COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS", "MAIN2")
        }

        session("multiple stops can be toggled in a single settings message") {
            sendSettings(
                stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set),
                stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
            )
            assertSoftly {
                enabledStops() shouldContain "DRAW"
                enabledStops() shouldNotContain "MAIN2"
                enabledStops() shouldBe setOf("DRAW", "MAIN1", "COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS")
            }
        }

        session("opponents scope does not affect human") {
            val before = enabledStops()
            sendSettings(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))
            enabledStops() shouldBe before
        }

        session("AnyPlayer scope applies to human") {
            enabledStops() shouldNotContain "END_OF_TURN"
            sendSettings(stop(StopType.EndStep_ad1f, SettingScope.AnyPlayer, SettingStatus.Set))
            enabledStops() shouldBe setOf("MAIN1", "COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS", "MAIN2", "END_OF_TURN")
        }

        session("settings response is echoed back as raw message") {
            sendSettings(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
            allRawMessages
                .flatMap { it.greToClientEvent.greToClientMessagesList }
                .filter {
                    it.type == GREMessageType.SetSettingsResp_695e
                }.shouldHaveSize(1)
        }
    })
