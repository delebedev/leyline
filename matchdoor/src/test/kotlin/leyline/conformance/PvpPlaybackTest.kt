package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId

/**
 * Verify per-seat GamePlayback wiring in PvP and 1vAI modes.
 */
class PvpPlaybackTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: DualSeatHarness? = null
        afterEach { harness?.shutdown() }

        test("per-seat playback instances are registered for PvP") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            h.bridge.playbacks.size shouldBe 2
            h.bridge.playbacks.containsKey(SeatId(1)) shouldBe true
            h.bridge.playbacks.containsKey(SeatId(2)) shouldBe true
        }

        test("1vAI has single playback for seat 1") {
            val h = MatchFlowHarness(seed = 42L)
            h.connectAndKeep()

            h.bridge.playbacks.size shouldBe 1
            h.bridge.playbacks.containsKey(SeatId(1)) shouldBe true

            h.bridge.shutdown()
        }
    })
