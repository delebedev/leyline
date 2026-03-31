package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forge.ai.simulation.SpellAbilityPicker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.game.PuzzleSource
import leyline.game.StateMapper
import leyline.game.mapper.ActionMapper
import leyline.match.MatchSession
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
 * Client errors: use `scry state` or `scry serve` (port 8091) instead.
 */
@Suppress("LargeClass")
class DebugServer(
    private val port: Int = 8090,
    private val debugCollector: DebugCollector? = null,
    private val gameStateCollector: GameStateCollector? = null,
    private val fdCollector: FdDebugCollector? = null,
    private val eventBus: DebugEventBus? = null,
    /** Runtime puzzle holder — set/cleared by POST /api/puzzle. */
    private val runtimePuzzle: AtomicReference<String?>? = null,
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
            "/api/fd-messages" to ::serveFdMessages,
            "/api/priority-log" to ::servePriorityLog,
            "/api/best-play" to ::serveBestPlay,
        ).forEach { (path, handler) ->
            srv.createContext(path) { ex -> safe(ex) { handler(ex) } }
        }

        srv.postContext("/api/inject-full", ::serveInjectFull)
        srv.createContext("/api/puzzle") { ex ->
            try {
                when (ex.requestMethod) {
                    "GET" -> serveGetPuzzle(ex)
                    "POST" -> servePuzzle(ex)
                    else -> {
                        ex.sendResponseHeaders(405, -1)
                        ex.close()
                    }
                }
            } catch (t: Throwable) {
                log.error("/api/puzzle error: {}", t.message, t)
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

    /** Register a POST-only endpoint with standard error handling. */
    private fun HttpServer.postContext(path: String, handler: (HttpExchange) -> Unit) {
        createContext(path) { ex ->
            try {
                if (ex.requestMethod != "POST") {
                    ex.sendResponseHeaders(405, -1)
                    ex.close()
                    return@createContext
                }
                handler(ex)
            } catch (t: Throwable) {
                log.error("{} error: {}", path, t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {}
                }
            }
        }
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
        val player = bridge.getPlayer(session.seatId)
        if (player == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no player for seat ${session.seatId.value}"}""")
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
            viewingSeatId = session.seatId.value,
        ).gsm

        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId.value)
            .setGameStateMessage(fullGsm)
            .build()

        // Build fresh actions so the client has valid actions at the new gsId
        val actions = ActionMapper.buildActions(game, session.seatId.value, bridge)
        val greActions = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(counter.nextMsgId())
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId.value)
            .setActionsAvailableReq(actions)
            .build()

        session.sendBundledGRE(listOf(greGsm, greActions))

        // Update bridge snapshot so subsequent Diffs are computed against this Full
        bridge.snapshotDiffBaseline(fullGsm)

        val info = "Injected Full GSM gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
        log.info(info)
        respond(ex, 200, "text/plain", info)
    }

    private fun serveGetPuzzle(ex: HttpExchange) {
        val current = runtimePuzzle?.get()
        respondJson(ex, """{"puzzle":${if (current != null) "\"$current\"" else "null"}}""")
    }

    private fun servePuzzle(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText().trim()
        val fileParam = ex.requestURI.query
            ?.split("&")
            ?.associate { it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") } }
            ?.get("file")

        // No file param + no body → clear puzzle
        if (fileParam == null && body.isEmpty()) {
            runtimePuzzle?.set(null)
            respond(ex, 200, "text/plain", "Puzzle cleared")
            return
        }

        // Resolve puzzle file path when ?file= is given
        val puzzlePath = if (fileParam != null) {
            val testResDir = File("matchdoor/src/test/resources/puzzles")
            val pzlFile = File(testResDir, "$fileParam.pzl")
            if (pzlFile.exists()) {
                pzlFile.absolutePath
            } else {
                // Verify classpath resource exists
                val resource = javaClass.classLoader.getResource("puzzles/$fileParam.pzl")
                if (resource == null) {
                    respond(ex, 404, "text/plain", "Puzzle not found: $fileParam (checked ${pzlFile.absolutePath} and classpath)")
                    return
                }
                "puzzles/$fileParam.pzl"
            }
        } else {
            null
        }

        // Set runtime puzzle path for next match (only when file-based)
        if (puzzlePath != null) {
            runtimePuzzle?.set(puzzlePath)
        }

        // Try hot-swap if there's an active session
        val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
        val bridge = session?.gameBridge

        if (session != null && bridge != null) {
            GameBootstrap.initializeLocalization()

            val puzzle = when {
                body.isNotEmpty() -> PuzzleSource.loadFromText(body, "injected")
                puzzlePath != null -> {
                    val testResDir = File("matchdoor/src/test/resources/puzzles")
                    val pzlFile = File(testResDir, "$fileParam.pzl")
                    if (pzlFile.exists()) {
                        PuzzleSource.loadFromFile(pzlFile.absolutePath)
                    } else {
                        @Suppress("SwallowedException")
                        try {
                            PuzzleSource.loadFromResource("puzzles/$fileParam.pzl")
                        } catch (e: IllegalStateException) {
                            respond(ex, 404, "text/plain", "Puzzle not found: $fileParam")
                            return
                        }
                    }
                }
                else -> {
                    respond(ex, 400, "text/plain", "Unexpected state")
                    return
                }
            }

            val deletedIds = bridge.resetForPuzzle(puzzle)

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
                viewingSeatId = session.seatId.value,
            ).gsm

            val gsmWithDeletes = if (deletedIds.isNotEmpty()) {
                fullGsm.toBuilder().addAllDiffDeletedInstanceIds(deletedIds).build()
            } else {
                fullGsm
            }

            val greGsm = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(session.seatId.value)
                .setGameStateMessage(gsmWithDeletes)
                .build()

            val actions = ActionMapper.buildActions(game, session.seatId.value, bridge)
            val greActions = GREToClientMessage.newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setMsgId(counter.nextMsgId())
                .setGameStateId(gsId)
                .addSystemSeatIds(session.seatId.value)
                .setActionsAvailableReq(actions)
                .build()

            session.sendBundledGRE(listOf(greGsm, greActions))
            bridge.snapshotDiffBaseline(gsmWithDeletes)

            if (fileParam != null) {
                val info = "Puzzle '$fileParam' set + injected gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
                log.info(info)
                respond(ex, 200, "text/plain", info)
            } else {
                val meta = PuzzleSource.parseMetadata(body)
                val info = "Injected puzzle '${meta.name}' gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
                log.info(info)
                respond(ex, 200, "text/plain", info)
            }
        } else {
            // No active session — just set runtime for next match
            if (fileParam != null) {
                respond(ex, 200, "text/plain", "Puzzle set: $fileParam (will activate on next Sparky match)")
            } else {
                respond(ex, 400, "text/plain", "No active session to inject body puzzle into")
            }
        }
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
