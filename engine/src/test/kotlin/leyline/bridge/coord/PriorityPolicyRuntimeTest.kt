package leyline.bridge.coord

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.settingsMessage
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.Stop
import wotc.mtgo.gre.external.messaging.Messages.StopType

class PriorityPolicyRuntimeTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("immutable settings command updates the runtime-owned stops") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)
            runtime.submit(
                PrioritySettingsCommand(
                    settings =
                        settingsMessage {
                            addStops(
                                Stop
                                    .newBuilder()
                                    .setStopType(StopType.UpkeepStep)
                                    .setAppliesTo(SettingScope.Team_ac6e)
                                    .setStatus(SettingStatus.Set)
                                    .build(),
                            )
                            addStops(
                                Stop
                                    .newBuilder()
                                    .setStopType(StopType.BeginCombatStep)
                                    .setAppliesTo(SettingScope.Opponents)
                                    .setStatus(SettingStatus.Set)
                                    .build(),
                            )
                        },
                    humanPlayerId = 1,
                    opponentPlayerId = 2,
                ),
            )

            assertSoftly {
                runtime.isPhaseStopped(1, PhaseType.UPKEEP).shouldBeTrue()
                runtime.hasOpponentStop(PhaseType.COMBAT_BEGIN).shouldBeTrue()
                runtime.isPhaseStopped(2, PhaseType.COMBAT_BEGIN).shouldBeTrue()
                runtime.enabledPhaseStops(1) shouldBe
                    setOf(
                        PhaseType.UPKEEP,
                        PhaseType.MAIN1,
                        PhaseType.COMBAT_DECLARE_ATTACKERS,
                        PhaseType.COMBAT_DECLARE_BLOCKERS,
                        PhaseType.MAIN2,
                    )
            }
        }

        test("full control and auto-pass values are decided by the runtime") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(
                PrioritySettingsCommand(
                    settings = settingsMessage { autoPassOption = AutoPassOption.ResolveAll },
                    humanPlayerId = null,
                    opponentPlayerId = null,
                ),
            )
            runtime.shouldAutoPass() shouldBe true

            runtime.submitAutoPassPriority(AutoPassPriority.No_a099)
            assertSoftly {
                runtime.isFullControl() shouldBe true
                runtime.shouldAutoPass() shouldBe false
            }
        }

        test("window mode has one runtime classification for own, opponent, and full control") {
            val runtime = PriorityPolicyRuntime()
            assertSoftly {
                runtime.priorityWindowMode(false, true, false, true, false, false) shouldBe PriorityWindowMode.Skip
                runtime.priorityWindowMode(false, true, false, true, true, false) shouldBe PriorityWindowMode.Visible
                runtime.priorityWindowMode(true, true, false, true, false, false) shouldBe PriorityWindowMode.Visible
            }
        }
    })
