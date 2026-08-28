package leyline.match

import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory

/**
 * Puzzle mode delegate — routed by per-match runtime config or `puzzle-<name>`
 * match IDs. When [puzzleIdentity] returns a non-null identity for a matchId,
 * the library resolves it; otherwise the matchId naming convention resolves
 * `data/puzzles/<name>.pzl`.
 *
 * **Ordering constraint:** localization is initialized by [PuzzleSource] before
 * any Forge puzzle construction, so this handler can be created eagerly.
 */
class PuzzleHandler(
    private val puzzleIdentity: (String) -> String?,
    private val cardRepository: CardRepository,
    private val registry: MatchRegistry,
    private val engineSettings: EngineSettings,
    private val puzzleLibrary: PuzzleLibrary,
    private val puzzleDefinition: (String) -> PuzzleDefinition? = { null },
    private val beforeRuntimeStart: ((GameBridge) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(PuzzleHandler::class.java)

    fun isPuzzleMatch(matchId: String): Boolean =
        puzzleIdentity(matchId) != null || puzzleDefinition(matchId) != null || matchId.startsWith("puzzle-")

    /**
     * Get or create the [GameBridge] for a puzzle match. Loads the puzzle file
     * on first call; subsequent calls for the same matchId return the existing
     * bridge.
     *
     * Callers then construct a [MatchSession] bound to this bridge and invoke
     * [sendPuzzleInitialBundle] to send the opening GRE bundle.
     */
    fun getOrCreatePuzzleBridge(matchId: String): GameBridge {
        val match =
            registry.getOrCreateMatch(matchId) {
                val bridge =
                    GameBridge(
                        matchId = matchId,
                        bridgeTimeoutMs = engineSettings.bridgeTimeoutMs,
                        promptFailsafeMs = engineSettings.promptFailsafeMs,
                        runtimeHorizonMode = RuntimeHorizonMode.Observed,
                        engineSettings = engineSettings,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(matchId, bridge).also {
                    val puzzle = loadPuzzleForMatch(matchId)
                    bridge.startPuzzle(
                        puzzle,
                        seed = engineSettings.seed,
                        beforeRuntimeStart = beforeRuntimeStart?.let { hook -> { hook(bridge) } },
                    )
                }
            }
        return match.bridge
    }

    /** Send puzzle initial bundle: ConnectResp + Full GSM (stage=Play) + ActionsAvailableReq. */
    fun sendPuzzleInitialBundle(
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ) {
        val bridge = session.gameBridge
        log.info("Match Door: puzzle mode, seat {} connected", seatId)
        check(session.preparePuzzleStart()) { "Puzzle start requires the human seat" }
        bridge.awaitPriority()
        val actionBridge = bridge.seat(SeatId(seatId)).action
        val pending = checkNotNull(actionBridge.getPending()) { "Puzzle priority window did not become pending" }
        val publication = bridge.cutCoordinator.lifecycle.publishPuzzleInitial(SeatId(seatId), pending.actionId)
        Tap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        if (publication.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY) {
            session.deliverRuntimeHorizon()
        } else {
            session.deliverLifecycle(bridge, beforeMsgId = publication.deliveryBoundaryMsgId)
        }
        if (publication.kind == leyline.bridge.handoff.PendingActionKind.DECLARE_ATTACKERS ||
            publication.kind == leyline.bridge.handoff.PendingActionKind.DECLARE_BLOCKERS
        ) {
            check(bridge.cutCoordinator.republishDeclaration(pending.actionId))
        }
        registry.getConnection(matchId, SeatId(seatId))?.armRuntimeDeliveryObserver()
    }

    /**
     * Load a configured identity or inline definition. Match loading receives
     * no filesystem path; the library owns identity-to-content resolution.
     */
    private fun loadPuzzleForMatch(matchId: String): forge.gamemodes.puzzle.Puzzle {
        val definition =
            puzzleDefinition(matchId)
                ?: puzzleLibrary.require(puzzleIdentity(matchId) ?: matchId.removePrefix("puzzle-"))
        return PuzzleSource.load(definition)
    }
}
