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
import leyline.testkit.stop
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

        test("settings update the runtime-owned stops") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)
            runtime.submit(
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

        test("installing a new game resets phase stops but keeps settings") {
            val runtime = PriorityPolicyRuntime()
            runtime.installPhaseStops(humanPlayerId = 1, opponentPlayerId = 2)
            runtime.submit(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            runtime.submit(settingsMessage { addStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set)) })

            runtime.installPhaseStops(humanPlayerId = 10, opponentPlayerId = 20)

            assertSoftly {
                runtime.isPhaseStopped(1, PhaseType.UPKEEP) shouldBe false
                runtime.isPhaseStopped(10, PhaseType.UPKEEP) shouldBe false
                runtime.isPhaseStopped(20, PhaseType.COMBAT_BEGIN) shouldBe true
                runtime.shouldAutoPass() shouldBe true
            }
        }

        test("submit accumulates stops and returns the authoritative settings") {
            val runtime = PriorityPolicyRuntime()
            val first = settingsMessage { addStops(stop(StopType.PostcombatMainPhase, SettingScope.Opponents, SettingStatus.Set)) }
            val second = settingsMessage { addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set)) }

            runtime.submit(first)
            val authoritative = runtime.submit(second)

            authoritative.stopsList.map { it.stopType }.toSet() shouldBe
                setOf(StopType.PostcombatMainPhase, StopType.EndStep_ad1f)
        }

        test("submit replaces a stop by type and scope") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(settingsMessage { addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set)) })

            val authoritative =
                runtime.submit(
                    settingsMessage { addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Clear_a3fe)) },
                )

            authoritative.stopsCount shouldBe 1
            authoritative.stopsList.single().status shouldBe SettingStatus.Clear_a3fe
        }

        test("submit accumulates transient stops") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(settingsMessage { addTransientStops(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set)) })

            val authoritative =
                runtime.submit(settingsMessage { addTransientStops(stop(StopType.DrawStep, SettingScope.Opponents, SettingStatus.Set)) })

            authoritative.transientStopsCount shouldBe 2
        }

        test("submit preserves a scalar when the delta is None") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })

            val authoritative =
                runtime.submit(settingsMessage { addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set)) })

            authoritative.autoPassOption shouldBe AutoPassOption.ResolveAll
        }

        test("submit updates a scalar when the delta is non-None") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })

            val authoritative = runtime.submit(settingsMessage { autoPassOption = AutoPassOption.FullControl })

            authoritative.autoPassOption shouldBe AutoPassOption.FullControl
        }

        test("submit keeps stop scopes independent") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(
                settingsMessage {
                    addStops(stop(StopType.EndStep_ad1f, SettingScope.Team_ac6e, SettingStatus.Set))
                    addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
                },
            )

            val authoritative =
                runtime.submit(
                    settingsMessage { addStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Clear_a3fe)) },
                )

            assertSoftly {
                authoritative.stopsCount shouldBe 2
                authoritative.stopsList.first { it.appliesTo == SettingScope.Team_ac6e }.status shouldBe SettingStatus.Set
                authoritative.stopsList.first { it.appliesTo == SettingScope.Opponents }.status shouldBe SettingStatus.Clear_a3fe
            }
        }

        test("full control and auto-pass values are decided by the runtime") {
            val runtime = PriorityPolicyRuntime()
            runtime.submit(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
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
            runtime.submit(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })

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
