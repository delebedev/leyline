package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.conformance.settingsMessage
import leyline.conformance.stop
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.StopType

class ClientSettingsMergeTest :
    FunSpec({

        tags(UnitTag)

        test("merge accumulates stops from sequential deltas") {
            val s1 = settingsMessage {
                addStops(stop(StopType.PostcombatMainPhase, SettingScope.Opponents, SettingStatus.Set))
            }
            val s2 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
            }

            val merged1 = MatchSession.mergeSettings(null, s1)
            val merged2 = MatchSession.mergeSettings(merged1, s2)

            merged2.stopsCount shouldBe 2
            merged2.stopsList.map { it.stopType }.toSet() shouldBe
                setOf(StopType.PostcombatMainPhase, StopType.EndStep_ad1f)
        }

        test("merge replaces same stop type + scope") {
            val s1 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
            }
            val s2 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Clear_a3fe))
            }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.stopsCount shouldBe 1
            merged.stopsList[0].status shouldBe SettingStatus.Clear_a3fe
        }

        test("merge accumulates transientStops") {
            val s1 = settingsMessage {
                addTransientStops(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set))
            }
            val s2 = settingsMessage {
                addTransientStops(stop(StopType.DrawStep, SettingScope.Opponents, SettingStatus.Set))
            }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.transientStopsCount shouldBe 2
        }

        test("merge preserves autoPassOption from existing when incoming is None") {
            val s1 = settingsMessage { autoPassOption = AutoPassOption.ResolveAll }
            val s2 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
            }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.autoPassOption shouldBe AutoPassOption.ResolveAll
        }

        test("merge updates autoPassOption when incoming is non-None") {
            val s1 = settingsMessage { autoPassOption = AutoPassOption.ResolveAll }
            val s2 = settingsMessage { autoPassOption = AutoPassOption.FullControl }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.autoPassOption shouldBe AutoPassOption.FullControl
        }

        test("merge accumulates stops across Team and Opponents scopes") {
            val s1 = settingsMessage {
                addStops(stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set))
            }
            val s2 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
            }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.stopsCount shouldBe 2
            // Different scopes — both preserved
            merged.stopsList.any {
                it.stopType == StopType.PrecombatMainPhase && it.appliesTo == SettingScope.Team_ac6e
            } shouldBe true
            merged.stopsList.any {
                it.stopType == StopType.EndStep_ad1f && it.appliesTo == SettingScope.Opponents
            } shouldBe true
        }

        test("merge replaces same stop type but different scope independently") {
            val s1 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Team_ac6e, SettingStatus.Set))
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
            }
            val s2 = settingsMessage {
                addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Clear_a3fe))
            }

            val merged = MatchSession.mergeSettings(s1, s2)
            merged.stopsCount shouldBe 2
            // Team scope still Set
            merged.stopsList.first {
                it.appliesTo == SettingScope.Team_ac6e
            }.status shouldBe SettingStatus.Set
            // Opponents scope now Clear
            merged.stopsList.first {
                it.appliesTo == SettingScope.Opponents
            }.status shouldBe SettingStatus.Clear_a3fe
        }
    })
