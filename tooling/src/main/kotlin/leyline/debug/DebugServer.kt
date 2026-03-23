package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forge.ai.simulation.SpellAbilityPicker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.analysis.SessionAnalyzer
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.game.PuzzleSource
import leyline.game.StateMapper
import leyline.game.mapper.ActionMapper
import leyline.match.MatchSession
import leyline.recording.RecordingInspector
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Embedded HTTP server for the Leyline debug panel.
 * Zero-dep JDK [HttpServer] on the given port (default 8090).
 * Intentionally avoids Ktor — Leyline uses raw Netty for transport,
 * and this debug panel doesn't justify adding the Ktor runtime.
 *
 * Endpoints:
 * - `GET /`               → leyline-debug.html from classpath
 * - `GET /api/messages`   → JSON array of entries (supports `?since=N`)
 * - `GET /api/state`      → match state snapshot
 * - `GET /api/id-map`     → instanceId cross-reference table
 * - `GET /api/game-states` → structured state snapshot timeline
 * - `GET /api/state-diff`  → diff between two gsIds (`?from=X&to=Y`)
 * - `GET /api/priority-events` → priority trace events
 * - `GET /api/instance-history` → zone history for an instanceId (`?id=N`)
 * - `GET /api/best-play`    → Forge AI oracle — best play for current board state
 * - `GET /api/recordings` → discover recording sessions on disk
 * - `GET /api/recording-summary?id=...` → compact summary for one session
 * - `GET /api/recording-actions?id=...` → extracted action timeline
 * - `GET /api/recording-compare?left=...&right=...` → action-level comparison
 * Client errors: use `scry state` or `scry serve` (port 8091) instead.
 */
@Suppress("LargeClass")
class DebugServer(
    private val port: Int = 8090,
    private val debugCollector: DebugCollector? = null,
    private val gameStateCollector: GameStateCollector? = null,
    private val fdCollector: FdDebugCollector? = null,
    private val eventBus: DebugEventBus? = null,
    private val recordingInspector: RecordingInspector = RecordingInspector(),
) {
    private val log = LoggerFactory.getLogger(DebugServer::class.java)
    private var server: HttpServer? = null

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)

        mapOf(
            "/" to ::serveHtml,
            "/api/messages" to ::serveMessages,
            "/api/state" to ::serveState,
            "/api/id-map" to ::serveIdMap,
            "/api/logs" to ::serveLogs,
            "/api/game-states" to ::serveGameStates,
            "/api/state-diff" to ::serveStateDiff,
            "/api/priority-events" to ::servePriorityEvents,
            "/api/instance-history" to ::serveInstanceHistory,
            "/api/recordings" to ::serveRecordings,
            "/api/recording-summary" to ::serveRecordingSummary,
            "/api/recording-actions" to ::serveRecordingActions,
            "/api/recording-compare" to ::serveRecordingCompare,
            "/api/recording-messages" to ::serveRecordingMessages,
            "/api/recording-analysis" to ::serveRecordingAnalysis,
            "/api/recording-events" to ::serveRecordingEvents,
            "/api/recording-invariants" to ::serveRecordingInvariants,
            "/api/recording-mechanics" to ::serveRecordingMechanics,
            "/api/fd-messages" to ::serveFdMessages,
            "/api/priority-log" to ::servePriorityLog,
            "/api/best-play" to ::serveBestPlay,
        ).forEach { (path, handler) ->
            srv.createContext(path) { ex -> safe(ex) { handler(ex) } }
        }

        srv.createContext("/api/inject-full") { ex ->
            try {
                if (ex.requestMethod != "POST") {
                    ex.sendResponseHeaders(405, -1)
                    ex.close()
                    return@createContext
                }
                serveInjectFull(ex)
            } catch (t: Throwable) {
                log.error("inject-full error: {}", t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {}
                }
            }
        }

        srv.createContext("/api/inject-puzzle") { ex ->
            try {
                if (ex.requestMethod != "POST") {
                    ex.sendResponseHeaders(405, -1)
                    ex.close()
                    return@createContext
                }
                serveInjectPuzzle(ex)
            } catch (t: Throwable) {
                log.error("inject-puzzle error: {}", t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {}
                }
            }
        }

        srv.createContext("/api/events") { ex ->
            try {
                if (ex.requestMethod != "GET") {
                    ex.sendResponseHeaders(405, -1)
                    ex.close()
                    return@createContext
                }
                serveSSE(ex)
            } catch (t: Throwable) {
                log.error("SSE error: {}", t.message)
                try {
                    ex.close()
                } catch (_: Throwable) {}
            }
        }
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "debug-http").apply { isDaemon = true }
        }
        srv.start()
        server = srv
        log.info("Debug panel: http://localhost:{}", port)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun serveHtml(ex: HttpExchange) {
        val html = javaClass.classLoader.getResourceAsStream("leyline-debug.html")
            ?.bufferedReader()?.readText()
        if (html == null) {
            respond(ex, 404, "text/plain", "leyline-debug.html not found on classpath")
        } else {
            respond(ex, 200, "text/html; charset=utf-8", html)
        }
    }

    private fun serveMessages(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val entries = debugCollector?.snapshot(since) ?: emptyList()
        val cursor = entries.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(entries), cursor)
    }

    private fun serveState(ex: HttpExchange) {
        val state = debugCollector?.matchState() ?: DebugCollector.MatchStateSnapshot()
        respondJson(ex, json.encodeToString(state))
    }

    /**
     * `/api/id-map` — instanceId cross-reference table with filtering.
     *
     * Params:
     * - `?active=true` — only active instanceIds (default: all)
     * - `?seat=1` or `?seat=2` — filter by owner seat
     * - `?zone=Battlefield` — filter by Forge zone name (case-insensitive)
     * - `?name=Prince` — filter by card name substring (case-insensitive)
     */
    private fun serveIdMap(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        var entries = debugCollector?.idMap() ?: emptyList()

        if (params["active"]?.lowercase() == "true") {
            entries = entries.filter { it.status == "active" }
        }
        params["seat"]?.toIntOrNull()?.let { seat ->
            entries = entries.filter { it.ownerSeatId == seat }
        }
        params["zone"]?.let { zone ->
            entries = entries.filter { it.forgeZone.equals(zone, ignoreCase = true) }
        }
        params["name"]?.let { name ->
            entries = entries.filter { it.cardName.contains(name, ignoreCase = true) }
        }

        respondJsonList(ex, json.encodeToString(entries), null)
    }

    private fun serveLogs(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val level = params["level"] ?: "DEBUG"
        val logs = debugCollector?.logSnapshot(since, level) ?: emptyList()
        val cursor = logs.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(logs), cursor)
    }

    // --- GameStateCollector endpoints ---

    private fun serveGameStates(ex: HttpExchange) {
        val timeline = gameStateCollector?.timeline() ?: emptyList()
        val cursor = timeline.maxOfOrNull { it.gsId }
        respondJsonList(ex, json.encodeToString(timeline), cursor)
    }

    private fun serveStateDiff(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)

        // Shortcut: ?last=N diffs from N snapshots back to the latest
        val last = params["last"]?.toIntOrNull()
        if (last != null) {
            val timeline = gameStateCollector?.timeline() ?: emptyList()
            if (timeline.size < 2) {
                respond(ex, 404, "text/plain", "Need at least 2 snapshots for ?last diff")
                return
            }
            val toIdx = timeline.size - 1
            val fromIdx = (toIdx - last).coerceAtLeast(0)
            val diff = gameStateCollector?.diff(timeline[fromIdx].gsId, timeline[toIdx].gsId)
            if (diff == null) {
                respond(ex, 404, "text/plain", "Snapshot not found")
                return
            }
            respondJson(ex, json.encodeToString(diff))
            return
        }

        val from = params["from"]?.toIntOrNull()
        val to = params["to"]?.toIntOrNull()
        if (from == null || to == null) {
            respond(ex, 400, "text/plain", "Required: ?from=<gsId>&to=<gsId> or ?last=<N>")
            return
        }
        val diff = gameStateCollector?.diff(from, to)
        if (diff == null) {
            respond(ex, 404, "text/plain", "Snapshot not found for gsId=$from or gsId=$to")
            return
        }
        respondJson(ex, json.encodeToString(diff))
    }

    private fun servePriorityEvents(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val events = gameStateCollector?.events(since) ?: emptyList()
        val cursor = events.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(events), cursor)
    }

    private fun serveInstanceHistory(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]?.toIntOrNull()
        if (id == null) {
            respond(ex, 400, "text/plain", "Required: ?id=<instanceId>")
            return
        }
        val history = gameStateCollector?.instanceHistory(id) ?: emptyList()
        respondJsonList(ex, json.encodeToString(history), null)
    }

    private fun serveRecordings(ex: HttpExchange) {
        val sessions = recordingInspector.listSessions()
        respondJsonList(ex, json.encodeToString(sessions), null)
    }

    private fun serveRecordingSummary(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val summary = recordingInspector.summary(id)
        if (summary == null) {
            respond(ex, 404, "text/plain", "Recording session not found or not parseable")
            return
        }
        respondJson(ex, json.encodeToString(summary))
    }

    private fun serveRecordingActions(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val card = params["card"]
        val actor = params["actor"]
        val limit = params["limit"]?.toIntOrNull() ?: 1000
        val actions = recordingInspector.actions(id, cardFilter = card, actorFilter = actor, limit = limit)
        respondJsonList(ex, json.encodeToString(actions), null)
    }

    private fun serveRecordingMessages(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val messages = recordingInspector.messages(id)
        if (messages == null) {
            respond(ex, 404, "text/plain", "Recording session not found or not parseable")
            return
        }
        respondJsonList(ex, json.encodeToString(messages), null)
    }

    private fun serveRecordingCompare(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val left = params["left"]
        val right = params["right"]
        if (left.isNullOrBlank() || right.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?left=<sessionId>&right=<sessionId>")
            return
        }
        val diff = recordingInspector.compare(left, right)
        if (diff == null) {
            respond(ex, 404, "text/plain", "Could not compare sessions (missing or unparsable)")
            return
        }
        respondJson(ex, json.encodeToString(diff))
    }

    // --- Recording analysis endpoints ---

    private fun serveRecordingAnalysis(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val recordingDir = recordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        // Resolve session root: recording dir may be a leaf (engine/, capture/payloads/)
        // but analysis.json lives at the session root (parent of engine/ or grandparent of capture/payloads/)
        val sessionDir = recordingInspector.resolveSessionRoot(recordingDir)
        // Read existing analysis or run on demand
        val analysis = SessionAnalyzer.readAnalysis(sessionDir)
            ?: SessionAnalyzer.analyze(sessionDir)
        if (analysis == null) {
            respond(ex, 404, "text/plain", "No analysis available (no messages)")
            return
        }
        respondJson(ex, json.encodeToString(analysis))
    }

    private fun serveRecordingEvents(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val recordingDir = recordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        val sessionDir = recordingInspector.resolveSessionRoot(recordingDir)
        val eventsFile = File(sessionDir, "events.jsonl")
        if (!eventsFile.exists()) {
            respondJsonList(ex, "[]", null)
            return
        }
        val streamFilter = params["stream"]
        val sinceSeq = params["since"]?.toIntOrNull() ?: 0

        val lines = eventsFile.readLines()
            .filter { it.isNotBlank() }
            .let { allLines ->
                if (streamFilter != null || sinceSeq > 0) {
                    allLines.filter { line ->
                        val streamOk = streamFilter == null || line.contains("\"stream\":\"$streamFilter\"")
                        val seqOk = sinceSeq <= 0 ||
                            run {
                                val seqMatch = Regex("\"seq\":(\\d+)").find(line)
                                val lineSeq = seqMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                lineSeq > sinceSeq
                            }
                        streamOk && seqOk
                    }
                } else {
                    allLines
                }
            }

        respondJson(ex, "{\"version\":1,\"data\":[${lines.joinToString(",")}]}")
    }

    private fun serveRecordingInvariants(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val recordingDir = recordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        val sessionDir = recordingInspector.resolveSessionRoot(recordingDir)
        val analysis = SessionAnalyzer.readAnalysis(sessionDir)
        if (analysis == null) {
            respondJsonList(ex, "[]", null)
            return
        }
        respondJsonList(ex, "[]", null)
    }

    private fun serveRecordingMechanics(ex: HttpExchange) {
        val manifest = SessionAnalyzer.readManifest()
        respondJson(ex, json.encodeToString(manifest.sorted()))
    }

    // --- Priority decision log ---

    /**
     * `/api/priority-log` — combined priority decision log from
     * [AutoPassEngine] (session thread) and [WebPlayerController] (engine thread).
     */
    private fun servePriorityLog(ex: HttpExchange) {
        val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
        if (session == null) {
            respondJsonList(ex, "[]", null)
            return
        }

        @Serializable
        data class Entry(val ts: Long, val source: String, val phase: String?, val turn: Int, val decision: String)

        val entries = mutableListOf<Entry>()

        // AutoPassEngine decisions (session thread)
        for (e in session.autoPassEngine.decisionLog()) {
            entries.add(Entry(e.ts, "session", e.phase, e.turn, e.decision.toString()))
        }

        // WebPlayerController decisions (engine thread)
        val bridge = session.gameBridge
        val controller = bridge?.humanController
        if (controller != null) {
            for (e in controller.decisionLog()) {
                entries.add(Entry(e.ts, "engine", e.phase, e.turn, e.decision.toString()))
            }
        }

        entries.sortBy { it.ts }

        respondJsonList(ex, json.encodeToString(entries), null)
    }

    // --- Forge Oracle ---

    /**
     * `GET /api/best-play` — asks the Forge AI simulation engine what the best
     * play is for the current board state. Uses [SpellAbilityPicker] which runs
     * Monte Carlo simulations to evaluate every legal play.
     *
     * Returns the single best action with card name, IDs, action type, and score.
     *
     * // TODO: return top-N alternatives by scoring each candidate individually
     * // via evaluateSa() on getCandidateSpellsAndAbilities(). Expensive but
     * // gives the agent a ranked list of options with scores.
     */
    private fun serveBestPlay(ex: HttpExchange) {
        val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
        if (session == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no active session"}""")
            return
        }
        val bridge = session.gameBridge
        if (bridge == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no game bridge"}""")
            return
        }
        val game = bridge.getGame()
        if (game == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no game"}""")
            return
        }
        val player = bridge.getPlayer(leyline.bridge.SeatId(session.seatId))
        if (player == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no player for seat ${session.seatId}"}""")
            return
        }

        try {
            val picker = SpellAbilityPicker(game, player)
            val bestSa = picker.chooseSpellAbilityToPlay(null)
            val score = picker.getScoreForChosenAbility()
            val phase = game.phaseHandler.phase?.toString()
            val turn = game.phaseHandler.turn

            if (bestSa == null) {
                respondJson(ex, """{"bestPlay":null,"phase":"$phase","turn":$turn,"reason":"no beneficial play"}""")
                return
            }

            val card = bestSa.hostCard
            val cardName = card?.name ?: "unknown"
            val forgeCardId = card?.id ?: -1
            val arenaInstanceId = try {
                bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value
            } catch (_: Exception) {
                -1
            }

            val actionType = when {
                bestSa.isSpell && card?.isLand == true -> "PlayLand"
                bestSa.isSpell -> "CastSpell"
                bestSa.isActivatedAbility -> "ActivateAbility"
                else -> "Unknown"
            }

            val saDesc = SpellAbilityPicker.abilityToString(bestSa, true)

            respondJson(
                ex,
                json.encodeToString(
                    BestPlayResponse(
                        bestPlay = BestPlayEntry(
                            cardName = cardName,
                            forgeCardId = forgeCardId,
                            arenaInstanceId = arenaInstanceId,
                            actionType = actionType,
                            score = score.value,
                            description = saDesc,
                        ),
                        phase = phase,
                        turn = turn,
                        reason = null,
                    ),
                ),
            )
        } catch (t: Throwable) {
            log.warn("best-play simulation failed: {}", t.message, t)
            respondJson(ex, """{"bestPlay":null,"reason":"simulation error: ${t.message?.replace("\"", "'")}"}""")
        }
    }

    @Serializable
    private data class BestPlayEntry(
        val cardName: String,
        val forgeCardId: Int,
        val arenaInstanceId: Int,
        val actionType: String,
        val score: Int,
        val description: String,
    )

    @Serializable
    private data class BestPlayResponse(
        val bestPlay: BestPlayEntry?,
        val phase: String?,
        val turn: Int = 0,
        val reason: String? = null,
    )

    // --- Front Door messages ---

    private fun serveFdMessages(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val entries = fdCollector?.snapshot(since) ?: emptyList()
        val cursor = entries.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(entries), cursor)
    }

    // --- Inject Full GSM (feasibility experiment) ---

    /**
     * POST `/api/inject-full` — rebuild current engine state as a Full GSM and
     * send it to the connected client. Tests whether the client accepts a
     * mid-game Full state replacement without glitching.
     *
     * No request body needed — reads state from the live Forge engine.
     */
    private fun serveInjectFull(ex: HttpExchange) {
        val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
        if (session == null) {
            respond(ex, 404, "text/plain", "No active session")
            return
        }
        val bridge = session.gameBridge
        if (bridge == null) {
            respond(ex, 404, "text/plain", "No game bridge")
            return
        }
        val game = bridge.getGame()
        if (game == null) {
            respond(ex, 404, "text/plain", "No game")
            return
        }

        val counter = session.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        // Build a Full GSM from current engine state (viewingSeatId=1 for hand visibility)
        val fullGsm = StateMapper.buildFromGame(
            game,
            gsId,
            session.matchId,
            bridge,
            updateType = GameStateUpdate.SendAndRecord,
            viewingSeatId = session.seatId,
        ).gsm

        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId)
            .setGameStateMessage(fullGsm)
            .build()

        // Build fresh actions so the client has valid actions at the new gsId
        val actions = ActionMapper.buildActions(game, session.seatId, bridge)
        val greActions = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(counter.nextMsgId())
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId)
            .setActionsAvailableReq(actions)
            .build()

        session.sendBundledGRE(listOf(greGsm, greActions))

        // Update bridge snapshot so subsequent Diffs are computed against this Full
        bridge.snapshotDiffBaseline(fullGsm)

        val info = "Injected Full GSM gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
        log.info(info)
        respond(ex, 200, "text/plain", info)
    }

    /**
     * POST `/api/inject-puzzle` — hot-swap the running game to a new puzzle.
     *
     * Accepts raw `.pzl` content in the request body, or `?file=<name>` to load
     * from test resources (e.g. `?file=bolt-face` → `puzzles/bolt-face.pzl`).
     *
     * Tears down the current game, clears all bridge state, starts a fresh puzzle
     * game, and injects its Full GSM + actions into the connected client.
     */
    private fun serveInjectPuzzle(ex: HttpExchange) {
        val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
        if (session == null) {
            respond(ex, 404, "text/plain", "No active session")
            return
        }
        val bridge = session.gameBridge
        if (bridge == null) {
            respond(ex, 404, "text/plain", "No game bridge")
            return
        }

        // Load puzzle: body content takes precedence, then ?file= query param
        val body = ex.requestBody.bufferedReader().readText().trim()
        val fileParam = ex.requestURI.query
            ?.split("&")
            ?.associate { it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") } }
            ?.get("file")

        // Puzzle constructor triggers GameState.<clinit> which needs localization
        GameBootstrap.initializeLocalization()

        val puzzle = when {
            body.isNotEmpty() -> PuzzleSource.loadFromText(body, "injected")
            fileParam != null -> {
                // Try filesystem first (test resources), then classpath
                val testResDir = File("matchdoor/src/test/resources/puzzles")
                val pzlFile = File(testResDir, "$fileParam.pzl")
                if (pzlFile.exists()) {
                    PuzzleSource.loadFromFile(pzlFile.absolutePath)
                } else {
                    @Suppress("SwallowedException") // resource-not-found → 404, exception carries no useful info
                    try {
                        PuzzleSource.loadFromResource("puzzles/$fileParam.pzl")
                    } catch (e: IllegalStateException) {
                        respond(ex, 404, "text/plain", "Puzzle not found: $fileParam (checked ${pzlFile.absolutePath} and classpath)")
                        return
                    }
                }
            }
            else -> {
                respond(ex, 400, "text/plain", "Provide .pzl content in body or ?file=<name> query param")
                return
            }
        }

        // Reset bridge with new puzzle (shutdown → clear state → startPuzzle)
        val deletedIds = bridge.resetForPuzzle(puzzle)

        // Build and send Full GSM + actions from the new engine state
        val counter = session.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        val game = bridge.getGame()!!
        val fullGsm = StateMapper.buildFromGame(
            game,
            gsId,
            session.matchId,
            bridge,
            updateType = GameStateUpdate.SendAndRecord,
            viewingSeatId = session.seatId,
        ).gsm

        // Add diffDeletedInstanceIds so the client purges cached objects from the old puzzle
        val gsmWithDeletes = if (deletedIds.isNotEmpty()) {
            fullGsm.toBuilder().addAllDiffDeletedInstanceIds(deletedIds).build()
        } else {
            fullGsm
        }

        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId)
            .setGameStateMessage(gsmWithDeletes)
            .build()

        val actions = ActionMapper.buildActions(game, session.seatId, bridge)
        val greActions = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(counter.nextMsgId())
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId)
            .setActionsAvailableReq(actions)
            .build()

        session.sendBundledGRE(listOf(greGsm, greActions))
        bridge.snapshotDiffBaseline(gsmWithDeletes)

        val meta = PuzzleSource.parseMetadata(if (body.isNotEmpty()) body else "")
        val label = if (fileParam != null) fileParam else meta.name
        val info = "Injected puzzle '$label' gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
        log.info(info)
        respond(ex, 200, "text/plain", info)
    }

    // --- Helpers ---

    private fun safe(ex: HttpExchange, block: () -> Unit) {
        try {
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, -1)
                ex.close()
                return
            }
            block()
        } catch (t: Throwable) {
            log.error("Debug server error on {}: {}", ex.requestURI, t.message, t)
            try {
                val trace = t.stackTrace.take(5).joinToString("\n  ") { it.toString() }
                respond(ex, 500, "text/plain", "Internal error [${t.javaClass.name}]: ${t.message}\n  $trace")
            } catch (_: Throwable) {
                try {
                    ex.close()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun respond(ex: HttpExchange, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondJson(ex: HttpExchange, body: String) =
        respond(ex, 200, "application/json; charset=utf-8", body)

    /** Wrap a list response in a versioned envelope with optional cursor. */
    private fun respondJsonList(ex: HttpExchange, data: String, cursor: Int?) {
        val cursorJson = if (cursor != null) ",\"cursor\":$cursor" else ""
        respondJson(ex, "{\"version\":1,\"data\":$data$cursorJson}")
    }

    /** SSE endpoint — pushes real-time debug events to connected clients. */
    private fun serveSSE(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val os = ex.responseBody

        val listener: (String, String) -> Unit = { type, data ->
            try {
                val msg = "event: $type\ndata: $data\n\n"
                synchronized(os) {
                    os.write(msg.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } catch (e: Exception) {
                log.debug("SSE send failed", e)
            }
        }

        eventBus?.addListener(listener)
        log.info("SSE client connected")
        try {
            while (true) {
                Thread.sleep(30_000)
                synchronized(os) {
                    os.write(":keepalive\n\n".toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            eventBus?.removeListener(listener)
            log.info("SSE client disconnected")
            try {
                ex.close()
            } catch (_: Throwable) {}
        }
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val key = URLDecoder.decode(part.substring(0, eq), Charsets.UTF_8)
                val value = URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
                key to value
            } else {
                null
            }
        }.toMap()
    }
}
