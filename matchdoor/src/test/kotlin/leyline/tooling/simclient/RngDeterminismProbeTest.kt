package leyline.tooling.simclient

import forge.util.MyRandom
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.config.ServerConfig
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import leyline.tooling.headless.TestCardRegistry
import java.util.Collections
import java.util.Random
import java.util.concurrent.locks.LockSupport

/** Logs every primitive draw off the shared RNG with a callsite hint. */
private class LoggingRandom(
    seed: Long,
) : Random(seed) {
    data class Draw(
        val index: Int,
        val bits: Int,
        val value: Int,
        val callsite: String,
    )

    val draws: MutableList<Draw> = Collections.synchronizedList(mutableListOf())

    override fun next(bits: Int): Int {
        val v = super.next(bits)
        val cs =
            Thread
                .currentThread()
                .stackTrace
                .firstOrNull {
                    val cn = it.className
                    cn.startsWith("forge.") || (cn.startsWith("leyline.") && !cn.contains("LoggingRandom"))
                }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"
        draws.add(Draw(draws.size, bits, v, cs))
        return v
    }
}

/**
 * Guards a load-bearing invariant for differential simclient analysis: given one
 * fixed seed, the opening shuffle + starting-player draw stream is the SAME whether
 * a game boots with the bridged-human controller (`GameBridge.start`, the simclient
 * boot path) or the Forge-AI registered controller (`GameBridge.startAiVsAi`).
 *
 * Both seed the same static `MyRandom`. If a future change makes one path consume
 * `MyRandom` differently before the deal completes, opening hands fork at turn 0 and
 * seed-matched comparison between the two paths becomes meaningless. This test fails
 * loudly if that happens.
 */
class RngDeterminismProbeTest :
    FunSpec({
        tags(UnitTag)

        val deck = "24 Forest\n36 Grizzly Bears"

        fun newBridge(): GameBridge {
            val cfg =
                MatchConfig(
                    ai = AiConfig(speed = 0.0),
                    server = ServerConfig(bridgeTimeoutMs = 5_000L, aiTurnWaitMs = 2_000L, mulliganWaitMs = 2_000L),
                )
            return GameBridge(
                bridgeTimeoutMs = cfg.server.bridgeTimeoutMs,
                promptFailsafeMs = cfg.server.promptFailsafeMs,
                matchConfig = cfg,
                messageCounter = MessageCounter(),
                cardRepository = TestCardRegistry.repo,
            )
        }

        fun snapshot(rng: LoggingRandom): List<LoggingRandom.Draw> = synchronized(rng.draws) { rng.draws.toList() }

        fun fingerprint(draws: List<LoggingRandom.Draw>) = draws.map { it.bits to it.value }

        test("opening shuffle draw stream is identical across boot paths") {
            val seed = 424242L
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureDeckRegistered(deck)

            // Candidate: bridged-human path; blocks at mulligan so setup draws are frozen.
            val candRng = LoggingRandom(seed)
            MyRandom.setRandom(candRng)
            val candBridge = newBridge()
            candBridge.start(seed = null, deckList1 = deck, deckList2 = deck)
            val candDraws = snapshot(candRng)
            val candHand1 = runCatching { candBridge.getHandGrpIds(SeatId(1)) }.getOrDefault(emptyList())
            val candHand2 = runCatching { candBridge.getHandGrpIds(SeatId(2)) }.getOrDefault(emptyList())
            val n = candDraws.size
            runCatching { candBridge.shutdown() }

            // Reference: AI-registered path; sample the first n draws then stop.
            val refRng = LoggingRandom(seed)
            MyRandom.setRandom(refRng)
            val refBridge = newBridge()
            refBridge.startAiVsAi(seed = null, deckList1 = deck, deckList2 = deck)
            val deadline = System.currentTimeMillis() + 10_000L
            while (refRng.draws.size < n && System.currentTimeMillis() < deadline) {
                LockSupport.parkNanos(5_000_000L)
            }
            val refDraws = snapshot(refRng).take(n)
            runCatching { refBridge.shutdown() }

            println(
                "RNG setup probe: seed=$seed candidate=$n draws ref=${refDraws.size} draws " +
                    "hand1=$candHand1 hand2=$candHand2",
            )

            fingerprint(refDraws) shouldBe fingerprint(candDraws)
        }
    })
