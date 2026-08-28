package leyline.match

import leyline.bridge.coord.GameOverIntent
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationLossReason
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
    private val peerSession: () -> SpectatorSession? = { null },
) : SessionOps {
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
            sent = deliverCommitted() || sent
        }
        sent = (peerSession()?.deliverCommitted() == true) || sent
        val game = gameBridge.getGame()
        if (!gameOverSent && game?.isGameOver == true) {
            sendGameOver()
            gameOverSent = true
            sent = true
        }
        return sent
    }

    override fun sendBundledGRE(messages: List<GREToClientMessage>) = sink.send(messages)

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        deliverCommitted()
    }

    internal fun deliverCommitted(): Boolean {
        var sent = false
        try {
            for (batch in gameBridge.cutCoordinator.drain(seatId)) {
                sendBundledGRE(batch)
                sent = true
            }
        } catch (ex: Exception) {
            gameBridge.cutCoordinator.failDelivery(ex)
        }
        return sent
    }

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
        deliverTerminal(winningTeam, reason)
        peerSession()?.deliverTerminal(winningTeam, reason)
    }

    private fun deliverTerminal(
        winningTeam: Int,
        reason: ResultReason,
    ) {
        deliverCommitted()
        sink.sendRaw(HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason))
    }
}
