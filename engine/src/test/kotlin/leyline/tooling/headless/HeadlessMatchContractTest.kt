package leyline.tooling.headless

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.IntegrationTag

/** The semantic seam is the test surface for the headless implementation. */
class HeadlessMatchContractTest :
    FunSpec({
        tags(IntegrationTag)

        test("the headless seam stays small and value-oriented") {
            val methodNames =
                HeadlessMatch::class.java.declaredMethods
                    .filterNot { it.isSynthetic }
                    .map { it.name.substringBefore('-').substringBefore('$') }
                    .toSet()
            assertSoftly {
                methodNames shouldBe
                    setOf("start", "submit", "advance", "observe", "checkpoint", "messagesSince", "advise", "query", "close")

                MatchFlowHarness::class.java
                    .getDeclaredField("bridge")
                    .type.name shouldBe "leyline.game.state.GameBridge"
                MatchObservation::class.java.getDeclaredField("messages").type shouldBe List::class.java
            }
        }

        test("normal startup and semantic intent produce an immutable observation") {
            val match = MatchFlowHarness.fromSpec(MatchSpec(seed = 42L))
            try {
                val started = match.start().observation
                assertSoftly {
                    started.messages.shouldNotBeEmpty()
                    started.turn shouldBe 1
                    started.phase shouldBe "MAIN1"

                    val checkpoint = match.checkpoint()
                    val action = match.submit(MatchIntent.PlayLand()).observation
                    action.messages.size shouldBeGreaterThan checkpoint.index
                    match.messagesSince(checkpoint).shouldNotBeEmpty()
                    action.client.objects.values
                        .shouldNotBeEmpty()
                    action.gameOver shouldBe false
                }
            } finally {
                match.close()
            }
        }

        test("puzzle startup, advancement, diagnostics, and cleanup cross the seam") {
            val puzzle =
                """
                [metadata]
                Name:Headless seam contract
                Goal:Survive
                Turns:2

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Forest
                humanlibrary=Forest
                ailibrary=Forest
                """.trimIndent()
            val match = MatchFlowHarness.fromSpec(MatchSpec(puzzleText = puzzle))
            val result =
                try {
                    match.start()
                    match.advance(AdvanceGoal.Until(maxPasses = 1))
                } finally {
                    val text = match.diagnostics("contract")
                    text shouldContain "session diagnostics"
                    match.close()
                }
            result.observation.messages.shouldNotBeEmpty()
            result.observation.turn shouldBe 1
        }
    })
