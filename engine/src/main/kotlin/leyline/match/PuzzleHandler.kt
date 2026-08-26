package leyline.match

import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.markPrompts
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
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
        output: MatchOutput,
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ) {
        val bridge = session.gameBridge
        log.info("Match Door: puzzle mode, seat {} connected", seatId)
        val gsId = session.counter.nextGsId()

        val (bundleMsg, nextMsgId) =
            HandshakeMessages.puzzleInitialBundle(
                SeatId(seatId),
                matchId,
                session.counter.currentMsgId(),
                gsId,
                bridge,
            )
        session.counter.setMsgId(nextMsgId)
        session.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        ProtoDump.dump(bundleMsg, "PuzzleInitialBundle-seat$seatId")
        output.send(bundleMsg)

        check(session.preparePuzzleStart()) { "Puzzle start requires the human seat" }
        bridge.awaitPriority()
        val actionBridge = bridge.seat(SeatId(seatId)).action
        val pending = checkNotNull(actionBridge.getPending()) { "Puzzle priority window did not become pending" }
        if (pending.state.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY) {
            bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId, includePriorityPrompt = false)
            registry.getConnection(matchId, SeatId(seatId))?.armRuntimeDeliveryObserver()
            return
        }
        val actions = bridge.bindInitialActionWindow(pending.actionId, gsId)

        // Expose the request only after its executable catalog is installed.
        val (actionsMsg, nextMsgId2) =
            HandshakeMessages.puzzleActionsReq(
                session.counter.currentMsgId(),
                gsId,
                SeatId(seatId),
                actions,
            )
        session.counter.setMsgId(nextMsgId2)
        markPrompts(session.counter, actionsMsg)
        Tap.outboundTemplate("PuzzleActionsReq seat=$seatId")
        ProtoDump.dump(actionsMsg, "PuzzleActionsReq-seat$seatId")
        output.send(actionsMsg)
        if (pending.state.kind == leyline.bridge.handoff.PendingActionKind.DECLARE_ATTACKERS ||
            pending.state.kind == leyline.bridge.handoff.PendingActionKind.DECLARE_BLOCKERS
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
