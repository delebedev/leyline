package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.testkit.MatchFlowHarness
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * Snapshot-shadow fidelity probe: drive a real game and, at each prompt, compare
 * the response the decision brain produces on the live game against the one it
 * produces on a game hydrated from that game's wire state.
 *
 * Locks two properties:
 *  - the probe is wired into the driver and accumulates per-prompt buckets, and
 *  - stack-independent decisions (priority-window action picks) hydrate
 *    faithfully — the hydrated game reproduces the live response byte-for-byte,
 *    so ActionsAvailableReq shows matches and zero mismatches.
 *
 * The known gaps (targeting/blocking, which depend on in-flight stack + attack
 * state hydration does not yet carry) are measured by the tool, not asserted
 * here — asserting a divergence would pin a fidelity debt in place.
 */
@Suppress("MissingAssertSoftly")
class SnapshotShadowProbeTest :
    FunSpec({
        tags(SimClientTag)

        test("stack-independent action picks hydrate faithfully (AAR match, no mismatch)") {
            val deck =
                """
                24 Forest
                36 Grizzly Bears
                """.trimIndent()
            val harness = MatchFlowHarness(seed = 42L, deckList = deck)
            val tempLog = Files.createTempFile("snapshot-shadow-", ".log").toFile()
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = tempLog.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "snapshot-shadow-test",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val driver = SimClientDriver(harness, playerLog, maxTurns = 12, snapshotShadow = true)
            driver.runOneGame()
            writer.close()

            val stats = driver.snapshotShadowStats()
            requireNotNull(stats) { "snapshotShadow was on, stats must be present" }

            val aar = stats[wotc.mtgo.gre.external.messaging.Messages.GREMessageType.ActionsAvailableReq_695e.name]
            requireNotNull(aar) { "a full game must have produced priority-window prompts" }
            aar.probed shouldBeGreaterThan 0
            aar.match shouldBeGreaterThan 0
            // Priority-window action selection does not depend on in-flight stack
            // state, so the hydrated game reproduces the live response exactly.
            aar.mismatch shouldBe 0
            aar.uncovered shouldBe 0
            aar.error shouldBe 0
        }
    })
