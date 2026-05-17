package leyline.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.SimClientTag
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * Drives a full game with Wildborn Preserver + non-Human creatures and asserts
 * that the bridge emits `NumericInputReq` when the trigger fires.
 *
 * Greedy policy casts everything in hand; once Wildborn is on the battlefield
 * and another non-Human creature ETBs, Forge fires the optional "may pay {X}"
 * trigger. If our `chooseNumber` / `announceRequirements` overrides are wired
 * correctly, the bridge emits a `NumericInputReq` (ChooseX) and the simclient
 * responder picks 0 to keep the game progressing.
 *
 * If `NumericInputReq` does not appear in the trace, the gap is in Forge's
 * X-announce path for optional triggers — instrumented `announceValuesLikeX`
 * prints in `PlaySpellAbility.java` will tell us why on stderr.
 */
class SimClientNumericInputTest :
    FunSpec({
        tags(SimClientTag)

        test("Wildborn Preserver triggers NumericInputReq when a non-Human creature ETBs") {
            val deck =
                """
                20 Forest
                20 Wildborn Preserver
                20 Centaur Courser
                """.trimIndent()
            val harness =
                MatchFlowHarness(
                    seed = 42L,
                    deckList = deck,
                )
            val tempLog = Files.createTempFile("simclient-numeric-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 2, 12, 0, 0)
            val writer = tempLog.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-numeric",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val driver = SimClientDriver(harness, playerLog, maxTurns = 50)
            driver.runOneGame()
            writer.close()

            val numericReqs =
                harness.allMessages
                    .filter { it.type == GREMessageType.NumericInputReq_695e }
            println(
                "SimClientNumericInputTest: log=${tempLog.absolutePath} " +
                    "(${tempLog.length()} bytes), NumericInputReq count=${numericReqs.size}",
            )

            assertSoftly {
                numericReqs.size shouldBeGreaterThan 0
                // First emit shape — invariants we expect across all ChooseX prompts.
                val first = numericReqs.first().numericInputReq
                first.numericInputType.name shouldContain "ChooseX"
                first.maxValue shouldBeGreaterThan 0
                first.stepSize shouldBe 1
            }
        }
    })
