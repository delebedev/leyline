package forge.nexus.bridge

import forge.game.Game
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the dedicated game thread that runs the engine's game loop.
 *
 * For constructed/commander games, [start] calls [forge.game.Match.startGame]
 * which handles: prepareAllZones → draw → mulligan → opening hand actions →
 * SBAs → triggers → startFirstTurn → mainGameLoop.
 *
 * For puzzles/sandbox, [startFromCurrentState] resumes from pre-initialized state.
 *
 * Lifecycle:
 * 1. [start] launches a daemon thread that calls `match.startGame(game)`
 * 2. When the loop reaches a priority stop, it blocks on [GameActionBridge]
 * 3. WS handler calls [GameActionBridge.submitAction] to unblock
 * 4. On disconnect / game reset, [shutdown] cancels pending and interrupts the thread
 */
class GameLoopController(
    val game: Game,
    private val actionBridges: Collection<GameActionBridge> = emptyList(),
    private val promptBridges: Collection<InteractivePromptBridge> = emptyList(),
    private val mulliganBridges: Collection<MulliganBridge> = emptyList(),
    private val onStateChanged: (suspend () -> Unit)? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(GameLoopController::class.java)
    }

    private var gameThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val started = CountDownLatch(1)

    /**
     * True while the game thread is alive and the loop hasn't ended.
     */
    val isRunning: Boolean get() = running.get()

    /**
     * Start the full game via the engine's [forge.game.Match.startGame].
     * Handles zone setup, coin flip, mulligan, opening hand actions, and
     * the main game loop — all on the game thread.
     */
    fun start() {
        launchGameThread("game-loop-${game.id}") {
            log.info("Game loop started for game ${game.id}, running match.startGame()")
            game.match.startGame(game)
        }
    }

    /**
     * Resume the game loop from an already-initialized state (e.g. puzzles).
     * Skips setupFirstTurn and goes straight into mainGameLoop.
     */
    fun startFromCurrentState() {
        launchGameThread("game-loop-puzzle-${game.id}") {
            log.info("Game loop (puzzle) started for game ${game.id}")
            // Puzzle state is pre-initialized (phase, battlefield, etc.).
            // mainGameLoop checks givePriorityToPlayer which defaults to false;
            // onStackResolved() is the public API to enable it.
            game.phaseHandler.onStackResolved()
            game.phaseHandler.mainGameLoop()
        }
    }

    private fun launchGameThread(name: String, block: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Game loop already running for game ${game.id}")
            return
        }
        gameThread = Thread({
            try {
                block()
                log.info("Game loop ended for game ${game.id}, gameOver=${game.isGameOver}")
            } catch (ex: Exception) {
                if (running.get()) {
                    log.error("Game loop crashed for game ${game.id}", ex)
                } else {
                    log.debug("Game loop interrupted during shutdown for game ${game.id}")
                }
            } finally {
                running.set(false)
                started.countDown()
            }
        }, name)
        gameThread!!.isDaemon = true
        gameThread!!.start()
        started.countDown()

        // Wire diagnostic context into bridges so timeout messages include
        // engine thread stack trace and game state. Only used on timeout path.
        val thread = gameThread!!
        actionBridges.forEach { it.setDiagnosticContext(game, thread) }
        promptBridges.forEach { it.setDiagnosticContext(game, thread) }
    }

    /**
     * Shut down the game loop. Cancels any pending bridge action and interrupts the thread.
     */
    fun shutdown() {
        if (!running.compareAndSet(true, false)) return

        log.info("Shutting down game loop for game ${game.id}")
        actionBridges.forEach { it.cancelPending() }
        promptBridges.forEach { it.cancelPending() }
        mulliganBridges.forEach { it.cancelPending() }
        gameThread?.interrupt()

        try {
            gameThread?.join(200)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        gameThread = null
    }

    /**
     * The daemon thread running the engine loop. Null before [start]/[startFromCurrentState].
     * Used by [BridgeTimeoutDiagnostic] to capture stack traces on timeout.
     */
    fun getEngineThread(): Thread? = gameThread

    /**
     * Wait for the game loop thread to start (useful in tests).
     */
    fun awaitStarted(timeoutMs: Long = 5_000): Boolean =
        started.await(timeoutMs, TimeUnit.MILLISECONDS)
}
