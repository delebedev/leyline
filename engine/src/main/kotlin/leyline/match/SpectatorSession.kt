package leyline.match

import leyline.bridge.coord.GameOverIntent
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Read-only session for MTGA-as-camera over a server-driven AI-vs-AI game. */
class SpectatorSession(
    override val seatId: SeatId,
    override val matchId: String,
    val sink: MessageSink,
    val gameBridge: GameBridge,
    val playerId: String = "spectator",
    override var counter: MessageCounter = gameBridge.messageCounter,
) : SessionOps {
    private val bundleBuilder = BundleBuilder(gameBridge, matchId, seatId.value)
    private var gameOverSent = false

    @Volatile private var closed = false

    private val pumpExecutor =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spectator-pump-${matchId.take(8)}-${seatId.value}").apply { isDaemon = true }
        }
    private var pumpTask: ScheduledFuture<*>? = null

    fun startPump(periodMs: Long = 50L) {
        if (closed) return
        if (pumpTask != null) return
        pumpTask =
            pumpExecutor.scheduleWithFixedDelay(
                {
                    val sent = pumpOnce()
                    if (gameOverSent && !sent) close()
                },
                0,
                periodMs,
                TimeUnit.MILLISECONDS,
            )
    }

    fun close() {
        closed = true
        pumpTask?.cancel(false)
        pumpTask = null
        pumpExecutor.shutdownNow()
    }

    /** Drain AI playback batches to the observer. Returns true when anything was sent. */
    fun pumpOnce(): Boolean {
        gameBridge.throwIfGameLoopFailed()
        var sent = false
        val playback = gameBridge.playbackFor(seatId)
        if (playback != null && playback.hasPendingMessages()) {
            for (batch in playback.drainQueue()) {
                sendBundledGRE(batch)
                sent = true
            }
        }
        val game = gameBridge.getGame()
        if (!gameOverSent && game?.isGameOver == true) {
            sendGameOver()
            gameOverSent = true
            sent = true
        }
        return sent
    }

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        for (m in messages) {
            if (m.hasGameStateMessage()) counter.markGameStateGsId(m.gameStateMessage.gameStateId)
        }
        sink.send(messages)
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = sendLegacySpectatorState(revealForSeat)

    /** Explicit residual projection path until spectator/multi-view ownership migrates. */
    private fun sendLegacySpectatorState(revealForSeat: Int?) {
        for (batch in gameBridge.cutCoordinator.drain(seatId)) sendBundledGRE(batch)
        val game = gameBridge.getGame() ?: return
        sendBundledGRE(bundleBuilder.stateOnlyDiff(game, counter, revealForSeat).messages)
    }

    override fun sendBundle(result: BundleBuilder.BundleResult) = sendBundledGRE(result.messages)

    override fun sendGameOver(reason: ResultReason) {
        val p1Won = gameBridge.getPlayer(SeatId(1))?.getOutcome()?.hasWon() == true
        val winningTeam = if (p1Won) 1 else 2
        gameBridge.cutCoordinator.publishGameOver(
            seatId,
            GameOverIntent(
                winningTeam = winningTeam,
                reason = reason,
                losingPlayerSeatId = 0,
                lossReason = AnnotationLossReason.LifeTotal,
            ),
        )
        gameBridge.cutCoordinator.drain(seatId).forEach(::sendBundledGRE)
        sink.sendRaw(HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason))
    }

    override fun paceDelay(multiplier: Int) {}
}
