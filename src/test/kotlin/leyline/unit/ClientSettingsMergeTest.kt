package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.server.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import wotc.mtgo.gre.external.messaging.Messages.Stop
import wotc.mtgo.gre.external.messaging.Messages.StopType

class ClientSettingsMergeTest :
    FunSpec({

        tags(UnitTag)

        fun stop(type: StopType, scope: SettingScope, status: SettingStatus): Stop =
            Stop.newBuilder()
                .setStopType(type)
                .setAppliesTo(scope)
                .setStatus(status)
                .build()

        test("merge accumulates stops from sequential deltas") {
            val s1 = SettingsMessage.newBuilder()
                .addStops(stop(StopType.PostcombatMainPhase, SettingScope.Opponents, SettingStatus.Set))
                .build()
            val s2 = SettingsMessage.newBuilder()
                .addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
                .build()

            val merged1 = MatchSession.mergeSettings(null, s1)
            val merged2 = MatchSession.mergeSettings(merged1, s2)

            merged2.stopsCount shouldBe 2
            merged2.stopsList.map { it.stopType }.toSet() shouldBe
                setOf(StopType.PostcombatMainPhase, StopType.EndStep_ad1f)
        }

        test("merge replaces same stop type + scope") {
            val s1 = SettingsMessage.newBuilder()
                .addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
                .build()
            val s2 = SettingsMessage.newBuilder()
                .addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Clear_a3fe))
                .build()

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.stopsCount shouldBe 1
            merged.stopsList[0].status shouldBe SettingStatus.Clear_a3fe
        }

        test("merge accumulates transientStops") {
            val s1 = SettingsMessage.newBuilder()
                .addTransientStops(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))
                .build()
            val s2 = SettingsMessage.newBuilder()
                .addTransientStops(stop(StopType.DrawStep, SettingScope.Opponents, SettingStatus.Set))
                .build()

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.transientStopsCount shouldBe 2
        }

        test("merge preserves autoPassOption from existing when incoming is None") {
            val s1 = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            val s2 = SettingsMessage.newBuilder()
                .addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
                .build()

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.autoPassOption shouldBe AutoPassOption.ResolveAll
        }

        test("merge updates autoPassOption when incoming is non-None") {
            val s1 = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            val s2 = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.FullControl)
                .build()

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.autoPassOption shouldBe AutoPassOption.FullControl
        }
    })
