package leyline.bridge.coord

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.SynchronizationContinuation
import leyline.bridge.types.AutoPassReason
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
                ),
            )
            runtime.shouldAutoPass() shouldBe true

            runtime.submitAutoPassPriority(AutoPassPriority.No_a099)
            assertSoftly {
                runtime.isFullControl() shouldBe true
                runtime.shouldAutoPass() shouldBe false
            }
        }

        test("one observation classifies own stops, opponent stops, sync, and skip") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)

            val ownStop = runtime.classifyPriorityWindow(observation(phase = PhaseType.DRAW))
            val ownSkip = ownStop.shouldBeInstanceOf<PriorityWindowDecision.Skip>()
            ownSkip.reason.shouldBeInstanceOf<AutoPassReason.PhaseNotStopped>().phase shouldBe "DRAW"

            runtime.submit(
                PrioritySettingsCommand(
                    settings =
                        settingsMessage {
                            addStops(
                                Stop
                                    .newBuilder()
                                    .setStopType(StopType.UpkeepStep)
                                    .setAppliesTo(SettingScope.Opponents)
                                    .setStatus(SettingStatus.Set)
                                    .build(),
                            )
                        },
                ),
            )
            assertSoftly {
                runtime.classifyPriorityWindow(observation(isOwnTurn = false, phase = PhaseType.UPKEEP)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
                runtime.classifyPriorityWindow(observation(stackEmpty = false)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = false)
                runtime.classifyPriorityWindow(observation()) shouldBe
                    PriorityWindowDecision.Skip(AutoPassReason.SmartPhaseSkip)
                runtime.classifyPriorityWindow(observation(hasMeaningfulAction = true)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
                runtime.classifyPriorityWindow(observation(promptJustResolved = true)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = false)
                runtime.classifyPriorityWindow(observation(smartPhaseSkip = false)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = false)
                runtime.classifyPriorityWindow(observation(forceVisible = true)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
                runtime.classifyPriorityWindow(
                    observation(continuation = SynchronizationContinuation.RequireVisible),
                ) shouldBe PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
            }
        }

        test("full control makes a stopped phase visible") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)
            runtime.submitAutoPassPriority(AutoPassPriority.No_a099)

            runtime.classifyPriorityWindow(observation(phase = PhaseType.DRAW)) shouldBe
                PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
        }

        test("classification carries the runtime auto-resolve value") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)
            runtime.submit(
                PrioritySettingsCommand(
                    settings = settingsMessage { autoPassOption = AutoPassOption.ResolveAll },
                ),
            )

            runtime.classifyPriorityWindow(observation(stackEmpty = false)) shouldBe
                PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = true)
        }
    })

private fun observation(
    isOwnTurn: Boolean = true,
    phase: PhaseType = PhaseType.MAIN1,
    smartPhaseSkip: Boolean = true,
    promptJustResolved: Boolean = false,
    stackEmpty: Boolean = true,
    forceVisible: Boolean = false,
    continuation: SynchronizationContinuation = SynchronizationContinuation.Reevaluate,
    hasMeaningfulAction: Boolean = false,
): PriorityWindowObservation =
    PriorityWindowObservation(
        isOwnTurn = isOwnTurn,
        phase = phase,
        smartPhaseSkip = smartPhaseSkip,
        promptJustResolved = promptJustResolved,
        stackEmpty = stackEmpty,
        forceVisible = forceVisible,
        continuation = continuation,
        hasMeaningfulAction = hasMeaningfulAction,
    )
