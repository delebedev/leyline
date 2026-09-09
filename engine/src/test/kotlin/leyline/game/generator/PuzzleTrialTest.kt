package leyline.game.generator

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchLaunchResponse
import leyline.copilot.CopilotProposal
import leyline.domain.service.MatchCoordinator
import leyline.game.data.ForgeCardRepository
import leyline.match.InProcessMatchRuntime
import leyline.match.MatchResultObservation
import leyline.match.MatchRuntime
import leyline.match.MatchRuntimeHandle
import leyline.match.MatchRuntimeLaunch
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.io.File
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

class PuzzleTrialTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("copilot decisions play an inline lethal puzzle to an observed win") {
            val trial = PuzzleTrial(runtime(), engineSeed = 42L)

            val result = trial.run(PuzzleDefinition("bolt-lethal", boltPuzzle))

            assertSoftly {
                result.status shouldBe PuzzleTrialStatus.Won
                result.definitionId shouldBe "bolt-lethal"
                result.engineSeed shouldBe 42L
                result.outcome?.won shouldBe true
                result.decisions.shouldNotBeEmpty()
                result.decisions.all { it.promptKey != null && it.gameStateId != null && it.respId != null } shouldBe true
                result.decisions.any { it.intent == "cast" } shouldBe true
            }
        }

        test("an unreachable win stops at its decision budget as inconclusive") {
            val result =
                PuzzleTrial(runtime(), engineSeed = 42L).run(
                    PuzzleDefinition("not-lethal", boltPuzzle.replace("AILife=3", "AILife=20")),
                    PuzzleTrialLimits(maxDecisions = 1, maxElapsedMillis = 5_000),
                )

            assertSoftly {
                result.status shouldBe PuzzleTrialStatus.DecisionBudgetExceeded
                result.outcome shouldBe null
                result.decisions.size shouldBe 1
                result.reason shouldContain "1"
            }
        }

        test("unsupported advice is explicit and the launched handle is always closed") {
            val closed = AtomicBoolean()
            val runtime = fakeRuntime(onClose = { closed.set(true) })

            val result = PuzzleTrial(runtime).run(PuzzleDefinition("unsupported", boltPuzzle))

            assertSoftly {
                result.status shouldBe PuzzleTrialStatus.Unsupported
                result.reason shouldContain "no copilot decoder"
                result.decisions shouldBe emptyList()
                closed.get() shouldBe true
            }
        }

        test("a close failure remains a structured engine result") {
            val runtime = fakeRuntime(onClose = { error("close broke") })

            val result = PuzzleTrial(runtime).run(PuzzleDefinition("close-failure", boltPuzzle))

            assertSoftly {
                result.status shouldBe PuzzleTrialStatus.EngineFailure
                result.reason shouldContain "runtime close failed: close broke"
            }
        }

        test("repeating one response for the same prompt reports a stall") {
            val response =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.PerformActionResp_097b)
                    .setGameStateId(1)
                    .setRespId(2)
                    .setSystemSeatId(1)
                    .build()
            val proposal =
                unavailableProposal.copy(
                    intent = "pass",
                    reason = null,
                    responses = listOf(HexFormat.of().formatHex(response.toByteArray())),
                )

            val result = PuzzleTrial(fakeRuntime(proposal)).run(PuzzleDefinition("stalled", boltPuzzle))

            assertSoftly {
                result.status shouldBe PuzzleTrialStatus.Stalled
                result.decisions.size shouldBe 1
                result.reason shouldContain "repeated the same response"
            }
        }
    })

private fun runtime() =
    InProcessMatchRuntime(
        EngineSettings(seed = 42L, bridgeTimeoutMs = 2_000L, promptFailsafeMs = 2_000L, aiSpeed = 0.0),
        MatchCoordinator.NOOP,
        ForgeCardRepository.open(),
        File("data/puzzles"),
    )

private val boltPuzzle =
    """
    [metadata]
    Name:Bolt lethal
    Goal:Win
    Turns:1
    Difficulty:Easy
    Description:Cast Lightning Bolt at the opponent.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=3

    humanhand=Lightning Bolt
    humanbattlefield=Mountain
    humanlibrary=Mountain
    ailibrary=Mountain
    """.trimIndent()

private val unavailableProposal =
    CopilotProposal(
        intent = "unrealizable",
        promptType = "PromptReq",
        seat = 1,
        promptKey = "1:2",
        gameStateId = 1,
        respId = 2,
        reason = "prompt type PromptReq has no copilot decoder",
    )

private fun fakeRuntime(
    proposal: CopilotProposal = unavailableProposal,
    onClose: () -> Unit = {},
): MatchRuntime =
    object : MatchRuntime {
        override fun launch(launch: MatchRuntimeLaunch): MatchRuntimeHandle =
            object : MatchRuntimeHandle {
                override val response =
                    RuntimeMatchLaunchResponse(
                        matchId = launch.config.matchId,
                        wireMatchId = launch.config.matchId,
                        accepted = true,
                        config = launch.config,
                    )
                override val result: CompletionStage<MatchResultObservation> = CompletableFuture()

                override fun receive(payload: ByteArray) = Unit

                override fun copilotProposal() = proposal

                override fun close() = onClose()
            }
    }
