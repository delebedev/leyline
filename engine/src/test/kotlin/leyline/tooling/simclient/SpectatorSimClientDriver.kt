package leyline.tooling.simclient

import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.SpectatorSession
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

data class SpectatorSimStats(
    val gameOver: Boolean,
    val totalMessages: Int,
    val durationMs: Long,
)

/** Drives a server-side AI-vs-AI game through SpectatorSession without client decisions. */
class SpectatorSimClientDriver(
    private val deckList: String,
    private val opponentDeckList: String = deckList,
    private val seed: Long = 42L,
    private val matchId: String = "simclient-spectator",
    private val log: PlayerLogWriter,
    private val maxDurationMs: Long = 20_000L,
) {
    fun runOneGame(): SpectatorSimStats {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()
        TestCardRegistry.ensureDeckRegistered(deckList)
        TestCardRegistry.ensureDeckRegistered(opponentDeckList)

        val bridge =
            GameBridge(
                cardRepository = TestCardRegistry.repo,
                matchConfig = MatchConfig(ai = AiConfig(speed = 0.0)),
            )
        val sink = ListMessageSink()
        val session = SpectatorSession(SeatId(1), matchId, sink, bridge)
        val readyForInitialBundle = CountDownLatch(1)
        val initialBundleSent = CountDownLatch(1)
        val startedAt = System.nanoTime()
        var totalMessages = 0

        try {
            bridge.startAiVsAi(
                seed = seed,
                deckList1 = deckList,
                deckList2 = opponentDeckList,
                startGameHook =
                    Runnable {
                        readyForInitialBundle.countDown()
                        initialBundleSent.await(10, TimeUnit.SECONDS)
                    },
            )
            check(readyForInitialBundle.await(10, TimeUnit.SECONDS)) { "AI-vs-AI game did not reach initial snapshot barrier" }

            val game = checkNotNull(bridge.getGame()) { "AI-vs-AI game missing after start" }
            val gsId = session.counter.nextGsId()
            val snap = GsmSnapshot.capture(game, bridge, matchId, gsId)
            val full =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        gsId,
                        matchId,
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snap, bridge),
                    ).finalizeAnnotations()
            bridge.applyMutations(full.mutations)
            bridge.bundleCursor.lastSent = snap
            val initial = greMessage(session.counter.nextMsgId(), full.gsm)
            log.writeBundle(listOf(initial))
            totalMessages++
            initialBundleSent.countDown()

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxDurationMs)
            while (!game.isGameOver && System.nanoTime() < deadline) {
                session.pumpOnce()
                if (sink.messages.isNotEmpty()) {
                    log.writeBundle(sink.messages)
                    totalMessages += sink.messages.size
                    sink.clear()
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
            session.pumpOnce()
            if (sink.messages.isNotEmpty()) {
                log.writeBundle(sink.messages)
                totalMessages += sink.messages.size
                sink.clear()
            }
            log.flush()

            return SpectatorSimStats(
                gameOver = game.isGameOver,
                totalMessages = totalMessages,
                durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        } finally {
            initialBundleSent.countDown()
            session.close()
            bridge.shutdown()
        }
    }

    private fun greMessage(
        msgId: Int,
        gsm: wotc.mtgo.gre.external.messaging.Messages.GameStateMessage,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsm.gameStateId)
            .addSystemSeatIds(1)
            .setGameStateMessage(gsm)
            .build()
}
