package leyline.match

import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.mapping.PriorityActionProjector
import leyline.game.state.GameBridge
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Puzzle mode delegate — routed by per-match runtime config or `puzzle-<name>`
 * match IDs. When [puzzlePath] returns a non-null path for a matchId, that file
 * is loaded; otherwise the matchId naming convention resolves `puzzles/<name>.pzl`.
 *
 * **Ordering constraint:** [GameBootstrap.initializeLocalization] must be called
 * before parsing a puzzle — Forge's `GameState.<clinit>` reads localized
 * card data. This is enforced inside [startPuzzleForMatch], not at construction time,
 * so the handler can be created eagerly without triggering Forge class loading.
 */
class PuzzleHandler(
    private val puzzlePath: (String) -> String?,
    private val cardRepository: CardRepository,
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
) {
    private val log = LoggerFactory.getLogger(PuzzleHandler::class.java)

    fun isPuzzleMatch(matchId: String): Boolean = puzzlePath(matchId) != null || matchId.startsWith("puzzle-")

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
                        bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                        promptFailsafeMs = matchConfig.server.promptFailsafeMs,
                        matchConfig = matchConfig,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(matchId, bridge).also { match ->
                    match.startPuzzle(resolvePuzzlePath(matchId))
                }
            }
        return match.bridge
    }

    /** Send puzzle initial bundle: ConnectResp + Full GSM (stage=Play) + ActionsAvailableReq. */
    fun sendPuzzleInitialBundle(
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ) = session.withSessionAuthority {
        val bridge = session.gameBridge
        log.info("Match Door: puzzle mode, seat {} connected", seatId)
        val gsId = session.counter.nextGsId()
        session.ensureEngineObservation()
        val observation = session.ctx.observation()
        val window =
            checkNotNull(observation.preparedPriorityWindows[SeatId(seatId)]) {
                "Puzzle priority window was not prepared before owner wake"
            }
        val projection = PriorityActionProjector.project(window, bridge::getOrAllocInstanceId)

        val (bundleMsg, nextMsgId) =
            HandshakeMessages.puzzleInitialBundle(
                SeatId(seatId),
                matchId,
                session.counter.currentMsgId(),
                gsId,
                bridge,
                observation.snapshot,
                projection.actions,
            )
        session.counter.setMsgId(nextMsgId)
        session.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        ProtoDump.dump(bundleMsg, "PuzzleInitialBundle-seat$seatId")
        session.sendMatchProgress(bundleMsg)
        bridge.activateInteractivePlayback()

        check(session.preparePuzzleStart()) { "Puzzle start requires the human seat" }
        val actionBridge = bridge.seat(SeatId(seatId)).action
        check(actionBridge.bindActionCatalog(window.actionId, gsId, projection.offers)) {
            "Puzzle priority actions did not bind to the pending window"
        }

        // Expose the request only after its executable catalog is installed.
        val (actionsMsg, nextMsgId2) =
            HandshakeMessages.puzzleActionsReq(
                session.counter.currentMsgId(),
                gsId,
                SeatId(seatId),
                projection.actions,
            )
        session.counter.setMsgId(nextMsgId2)
        Tap.outboundTemplate("PuzzleActionsReq seat=$seatId")
        ProtoDump.dump(actionsMsg, "PuzzleActionsReq-seat$seatId")
        session.sendMatchProgress(actionsMsg)
    }

    /**
     * Load puzzle: prefer the configured path/name, else the matchId convention.
     * The configured value may be an absolute path, a cwd-relative path, or a bare
     * puzzle name (e.g. `stock-up`) — bare names resolve to `puzzles/<name>.pzl`.
     */
    private fun resolvePuzzlePath(matchId: String): String {
        val name = puzzlePath(matchId) ?: matchId.removePrefix("puzzle-")
        return (
            resolvePuzzleFile(name)
                ?: error("Puzzle not found: $name (looked in ${File(findLeylineDir(), "puzzles").absolutePath})")
        ).absolutePath
    }

    /** First existing candidate: as-given, with `.pzl`, then under `puzzles/`. */
    private fun resolvePuzzleFile(name: String): File? {
        val cwd = File(System.getProperty("user.dir"))
        val puzzlesDir = File(findLeylineDir(), "puzzles")
        val withPzl = if (name.endsWith(".pzl")) name else "$name.pzl"

        fun atCwd(n: String) = File(n).let { if (it.isAbsolute) it else File(cwd, n) }
        return listOf(
            atCwd(name),
            atCwd(withPzl),
            File(puzzlesDir, name),
            File(puzzlesDir, withPzl),
        ).firstOrNull { it.isFile }
    }
}
