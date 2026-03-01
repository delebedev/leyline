package leyline.bridge

import forge.game.card.Card
import forge.game.card.CardCollectionView
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * CompletableFuture-based bridge for mulligan decisions.
 *
 * The engine's [forge.game.mulligan.MulliganService] runs on the game thread and
 * calls [forge.game.player.PlayerController.mulliganKeepHand] /
 * [forge.game.player.PlayerController.tuckCardsViaMulligan], both of which block
 * until the web client submits a decision via WebSocket.
 *
 * For tests, [autoKeep] mode auto-submits keep immediately.
 */
class MulliganBridge(
    private val autoKeep: Boolean = false,
    private val timeoutMs: Long = 60_000,
) {
    companion object {
        private val log = LoggerFactory.getLogger(MulliganBridge::class.java)
    }

    // Pending state — exposed for state DTO mapping
    @Volatile var pendingPhase: MulliganPhase? = null
        private set

    @Volatile var pendingMulliganCount: Int = 0
        private set

    @Volatile var pendingCardsToTuck: Int = 0
        private set

    @Volatile var pendingPlayerId: Int = -1
        private set

    /** Monotonic counter — increments each time a keep/tuck prompt is posted. */
    @Volatile var promptSequence: Int = 0
        private set

    private var keepFuture: CompletableFuture<Boolean>? = null
    private var tuckFuture: CompletableFuture<List<Card>>? = null

    /**
     * Called by [WebPlayerController.mulliganKeepHand] on the game thread.
     * Blocks until the WS client calls [submitKeep] or [submitMull].
     *
     * @return true to keep, false to mulligan
     */
    fun awaitKeepDecision(playerId: Int, mulliganCount: Int): Boolean {
        if (autoKeep) {
            log.debug("MulliganBridge: auto-keep for player {}", playerId)
            return true
        }

        val future = CompletableFuture<Boolean>()
        synchronized(this) {
            pendingPhase = MulliganPhase.WaitingKeep
            pendingMulliganCount = mulliganCount
            pendingCardsToTuck = 0
            pendingPlayerId = playerId
            keepFuture = future
            promptSequence++
        }
        log.info("MulliganBridge: awaiting keep/mull for player {} (mulls={})", playerId, mulliganCount)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.warn("MulliganBridge: timeout waiting for keep decision, auto-keeping")
            true
        } finally {
            synchronized(this) {
                pendingPhase = null
                keepFuture = null
            }
        }
    }

    /**
     * Called by [WebPlayerController.tuckCardsViaMulligan] on the game thread.
     * Blocks until the WS client calls [submitTuck].
     *
     * @return the cards to put on bottom of library
     */
    fun awaitTuckDecision(playerId: Int, count: Int, hand: CardCollectionView): List<Card> {
        if (autoKeep) {
            log.debug("MulliganBridge: auto-tuck {} for player {}", count, playerId)
            return hand.toList().take(count)
        }

        val future = CompletableFuture<List<Card>>()
        synchronized(this) {
            pendingPhase = MulliganPhase.WaitingTuck
            pendingMulliganCount = pendingMulliganCount // keep current count
            pendingCardsToTuck = count
            pendingPlayerId = playerId
            tuckFuture = future
            promptSequence++
        }
        log.info("MulliganBridge: awaiting tuck {} cards for player {}", count, playerId)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.warn("MulliganBridge: timeout waiting for tuck, auto-tucking first {}", count)
            hand.toList().take(count)
        } finally {
            synchronized(this) {
                pendingPhase = null
                tuckFuture = null
            }
        }
    }

    fun submitKeep() {
        synchronized(this) {
            keepFuture?.complete(true)
        }
    }

    fun submitMull() {
        synchronized(this) {
            keepFuture?.complete(false)
        }
    }

    fun submitTuck(cards: List<Card>) {
        synchronized(this) {
            tuckFuture?.complete(cards)
        }
    }

    fun cancelPending() {
        synchronized(this) {
            keepFuture?.cancel(true)
            tuckFuture?.cancel(true)
            pendingPhase = null
            keepFuture = null
            tuckFuture = null
        }
    }
}
