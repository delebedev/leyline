package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.EngineCut
import leyline.game.EngineCutCheckpoint
import leyline.game.InteractionReadiness
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only session for MTGA-as-camera over a server-driven AI-vs-AI game. */
internal class SpectatorSession(
    override val seatId: SeatId,
    override val matchId: String,
    val sink: MessageSink,
    val gameBridge: GameBridge,
    val playerId: String = "spectator",
    override var counter: MessageCounter = gameBridge.messageCounter,
    private val owner: MatchOwner,
) : SessionOps {
    private val bundleBuilder = BundleBuilder(gameBridge, matchId, seatId.value)
    private var gameOverSent = false
    private val closed = AtomicBoolean(false)
    private val engineCutListener: (EngineCutCheckpoint) -> Unit = ::requestDrain
    private val protocolHead =
        MatchProtocolHead(
            owner,
            MatchOutbox.Audience.Spectator(seatId),
            sink,
        )

    /**
     * Bind autonomous engine progress to the match owner.
     *
     * A new game installs this before its initial-state barrier is released.
     * Replacements reconcile values published before this binding only after
     * their Full state has been sent.
     */
    fun startObserving() {
        owner.reduce {
            if (closed.get()) return@reduce
            gameBridge.observeEngineCuts(engineCutListener)
        }
    }

    /** Deliver any pending engine values after this connection's Full state. */
    fun reconcilePendingEngineCuts() {
        owner.reduce {
            if (!closed.get()) {
                drainEngineCutsThrough(gameBridge.latestEngineCutCheckpoint())
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            gameBridge.stopObservingEngineCuts(engineCutListener)
            protocolHead.close()
        }
    }

    private fun requestDrain(checkpoint: EngineCutCheckpoint) {
        if (closed.get()) return
        owner.enqueue {
            if (!closed.get()) {
                drainEngineCutsThrough(checkpoint)
            }
        }
    }

    private fun drainEngineCutsThrough(checkpoint: EngineCutCheckpoint) {
        owner.assertOwnerThread()
        while (true) {
            when (val cut = gameBridge.peekEngineCutThrough(checkpoint) ?: break) {
                is EngineCut.Observation -> {
                    val results = bundleBuilder.playbackYield(cut.value, counter)
                    gameBridge.acknowledgeEngineCut(cut)
                    results.forEach { sendThroughOutbox(it.messages) }
                }
                is EngineCut.InteractionReady -> {
                    gameBridge.acknowledgeEngineCut(cut)
                    if (cut.kind == InteractionReadiness.GAME_OVER) {
                        sendGameOverOwned(ResultReason.Game_ae0a)
                    }
                }
            }
        }
    }

    override fun sendBundledGRE(messages: List<GREToClientMessage>) =
        owner.reduce {
            if (!closed.get()) sendThroughOutbox(messages)
        }

    private fun sendThroughOutbox(messages: List<GREToClientMessage>) {
        owner.assertOwnerThread()
        for (m in messages) {
            if (m.hasGameStateMessage()) counter.markGameStateGsId(m.gameStateMessage.gameStateId)
        }
        owner.observeOutbound(messages)
        owner.appendOutbox(listOf(protocolHead.token to MatchOutbox.Payload.Gre(messages)))
        protocolHead.flush()
    }

    override fun sendMatchProgress(message: MatchServiceToClientMessage) =
        owner.reduce {
            if (!closed.get()) sendMatchProgressOwned(message)
        }

    private fun sendMatchProgressOwned(message: MatchServiceToClientMessage) {
        owner.assertOwnerThread()
        owner.observeOutbound(message)
        owner.appendOutbox(listOf(protocolHead.token to MatchOutbox.Payload.Raw(message)))
        protocolHead.flush()
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = owner.reduce {
        if (!closed.get()) sendThroughOutbox(bundleBuilder.stateOnlyDiff(counter).messages)
    }

    override fun sendBundle(result: BundleBuilder.BundleResult) =
        owner.reduce {
            if (!closed.get()) sendThroughOutbox(result.messages)
        }

    override fun sendGameOver(reason: ResultReason) =
        owner.reduce {
            sendGameOverOwned(reason)
        }

    private fun sendGameOverOwned(reason: ResultReason) {
        owner.assertOwnerThread()
        if (closed.get() || gameOverSent) return
        val p1Won = gameBridge.playerWon(SeatId(1))
        val winningTeam = if (p1Won) 1 else 2
        sendThroughOutbox(bundleBuilder.gameOverBundle(winningTeam, counter, reason = reason).messages)
        sendMatchProgressOwned(HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason))
        gameOverSent = true
        protocolHead.afterDrained(::close)
    }

    override fun paceDelay(multiplier: Int) {}
}
