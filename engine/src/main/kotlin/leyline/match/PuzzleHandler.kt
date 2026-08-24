package leyline.match

import forge.gamemodes.puzzle.Puzzle
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.markPrompts
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
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
 * before any [Puzzle] constructor — Forge's `GameState.<clinit>` reads localized
 * card data. This is enforced inside [loadPuzzleForMatch], not at construction time,
 * so the handler can be created eagerly without triggering Forge class loading.
 */
class PuzzleHandler(
    private val puzzlePath: (String) -> String?,
    private val cardRepository: CardRepository,
    private val registry: MatchRegistry,
    private val engineSettings: EngineSettings,
    private val puzzlesDir: File,
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
                        matchId = matchId,
                        bridgeTimeoutMs = engineSettings.bridgeTimeoutMs,
                        promptFailsafeMs = engineSettings.promptFailsafeMs,
                        engineSettings = engineSettings,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(matchId, bridge).also {
                    val puzzle = loadPuzzleForMatch(matchId)
                    bridge.startPuzzle(puzzle, seed = engineSettings.seed)
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
    }

    /**
     * Load puzzle: prefer the configured path/name, else the matchId convention.
     * The configured value may be an absolute path or a bare puzzle name
     * (e.g. `stock-up`) — bare names resolve to `puzzles/<name>.pzl` under the
     * resolved puzzle root.
     */
    private fun loadPuzzleForMatch(matchId: String): Puzzle {
        // Puzzle constructor triggers GameState.<clinit> which needs localization
        GameBootstrap.initializeLocalization()

        val name = puzzlePath(matchId) ?: matchId.removePrefix("puzzle-")
        val file =
            resolvePuzzleFile(name)
                ?: error("Puzzle not found: $name (looked in ${puzzlesDir.absolutePath})")
        return PuzzleSource.loadFromFile(file.absolutePath)
    }

    /** First existing candidate: as-given absolute path, with `.pzl`, then under the resolved puzzle root. */
    private fun resolvePuzzleFile(name: String): File? {
        val withPzl = if (name.endsWith(".pzl")) name else "$name.pzl"

        return listOf(
            File(name).takeIf { it.isAbsolute },
            File(puzzlesDir, name),
            File(puzzlesDir, withPzl),
        ).firstOrNull { it != null && it.isFile }
    }
}
