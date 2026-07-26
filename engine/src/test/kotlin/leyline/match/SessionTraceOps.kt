package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import leyline.match.GameOps
import leyline.match.SessionContext
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Test double for [GameOps] that traces all calls for assertion.
 *
 * Always constructed with a [GameBridge] — handlers under test need
 * a non-null `bundleBuilder` and `gameBridge`.
 */
class SessionTraceOps(
    override val seatId: SeatId = SeatId(1),
    override val matchId: String = "test-match",
    override var counter: MessageCounter = MessageCounter(),
    override val gameBridge: GameBridge,
) : GameOps {
    override val bundleBuilder: BundleBuilder =
        BundleBuilder(gameBridge, matchId, seatId.value)

    /** Snapshot for handler construction in tests. */
    val ctx: SessionContext =
        SessionContext(
            gameBridge,
            object : EngineCutAwaiter {
                override fun awaitPriority(): Boolean = awaitEnginePriority()

                override fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean = awaitEnginePriorityWithTimeout(timeoutMs)

                override fun awaitActionPriority(): Boolean = awaitEnginePriority()
            },
        )

    override fun awaitEnginePriority(): Boolean = gameBridge.awaitPriorityWithTimeout(gameBridge.priorityWaitMs)

    override fun awaitEnginePriorityWithTimeout(timeoutMs: Long): Boolean = gameBridge.awaitPriorityWithTimeout(timeoutMs)

    // --- Traced calls ---

    val sentGRE = mutableListOf<List<GREToClientMessage>>()
    val sentRealGameState = mutableListOf<GameBridge>()
    val sentGameOver = mutableListOf<ResultReason>()
    val paceDelays = mutableListOf<Int>()

    val sendRealGameStateCount: Int get() = sentRealGameState.size
    val sendGameOverCount: Int get() = sentGameOver.size

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        sentGRE.add(messages)
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        sentRealGameState.add(bridge)
    }

    override fun sendBundle(result: BundleBuilder.BundleResult) {
        sentGRE.add(result.messages)
    }

    override fun sendGameOver(reason: ResultReason) {
        sentGameOver.add(reason)
    }

    override fun paceDelay(multiplier: Int) {
        paceDelays.add(multiplier)
    }
}
