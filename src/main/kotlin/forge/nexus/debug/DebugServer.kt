package forge.nexus.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forge.nexus.analysis.SessionAnalyzer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Embedded HTTP server for the nexus debug panel.
 * Zero-dep JDK [HttpServer] on the given port (default 8090).
 * Intentionally avoids Ktor — nexus uses raw Netty for transport,
 * and this debug panel doesn't justify adding the Ktor runtime.
 *
 * Endpoints:
 * - `GET /`               → nexus-debug.html from classpath
 * - `GET /api/messages`   → JSON array of entries (supports `?since=N`)
 * - `GET /api/state`      → match state snapshot
 * - `GET /api/id-map`     → instanceId cross-reference table
 * - `GET /api/game-states` → structured state snapshot timeline
 * - `GET /api/state-diff`  → diff between two gsIds (`?from=X&to=Y`)
 * - `GET /api/priority-events` → priority trace events
 * - `GET /api/instance-history` → zone history for an instanceId (`?id=N`)
 * - `GET /api/recordings` → discover recording sessions on disk
 * - `GET /api/recording-summary?id=...` → compact summary for one session
 * - `GET /api/recording-actions?id=...` → extracted action timeline
 * - `GET /api/recording-compare?left=...&right=...` → action-level comparison
 * - `GET /api/client-errors` → Player.log errors (supports `?since=N`, `?type=...`)
 */
class DebugServer(private val port: Int = 8090) {
    private val log = LoggerFactory.getLogger(DebugServer::class.java)
    private var server: HttpServer? = null

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/") { ex -> safe(ex) { serveHtml(ex) } }
        srv.createContext("/api/messages") { ex -> safe(ex) { serveMessages(ex) } }
        srv.createContext("/api/state") { ex -> safe(ex) { serveState(ex) } }
        srv.createContext("/api/id-map") { ex -> safe(ex) { serveIdMap(ex) } }
        srv.createContext("/api/logs") { ex -> safe(ex) { serveLogs(ex) } }
        srv.createContext("/api/game-states") { ex -> safe(ex) { serveGameStates(ex) } }
        srv.createContext("/api/state-diff") { ex -> safe(ex) { serveStateDiff(ex) } }
        srv.createContext("/api/priority-events") { ex -> safe(ex) { servePriorityEvents(ex) } }
        srv.createContext("/api/instance-history") { ex -> safe(ex) { serveInstanceHistory(ex) } }
        srv.createContext("/api/recordings") { ex -> safe(ex) { serveRecordings(ex) } }
        srv.createContext("/api/recording-summary") { ex -> safe(ex) { serveRecordingSummary(ex) } }
        srv.createContext("/api/recording-actions") { ex -> safe(ex) { serveRecordingActions(ex) } }
        srv.createContext("/api/recording-compare") { ex -> safe(ex) { serveRecordingCompare(ex) } }
        srv.createContext("/api/recording-messages") { ex -> safe(ex) { serveRecordingMessages(ex) } }
        srv.createContext("/api/recording-analysis") { ex -> safe(ex) { serveRecordingAnalysis(ex) } }
        srv.createContext("/api/recording-events") { ex -> safe(ex) { serveRecordingEvents(ex) } }
        srv.createContext("/api/recording-invariants") { ex -> safe(ex) { serveRecordingInvariants(ex) } }
        srv.createContext("/api/recording-mechanics") { ex -> safe(ex) { serveRecordingMechanics(ex) } }
        srv.createContext("/api/client-errors") { ex -> safe(ex) { serveClientErrors(ex) } }
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
        val html = javaClass.classLoader.getResourceAsStream("nexus-debug.html")
            ?.bufferedReader()?.readText()
        if (html == null) {
            respond(ex, 404, "text/plain", "nexus-debug.html not found on classpath")
        } else {
            respond(ex, 200, "text/html; charset=utf-8", html)
        }
    }

    private fun serveMessages(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val entries = NexusDebugCollector.snapshot(since)
        val cursor = entries.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(entries), cursor)
    }

    private fun serveState(ex: HttpExchange) {
        val state = NexusDebugCollector.matchState()
        respondJson(ex, json.encodeToString(state))
    }

    private fun serveIdMap(ex: HttpExchange) {
        val map = NexusDebugCollector.idMap()
        respondJsonList(ex, json.encodeToString(map), null)
    }

    private fun serveLogs(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val level = params["level"] ?: "DEBUG"
        val logs = NexusDebugCollector.logSnapshot(since, level)
        val cursor = logs.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(logs), cursor)
    }

    // --- GameStateCollector endpoints ---

    private fun serveGameStates(ex: HttpExchange) {
        val timeline = GameStateCollector.timeline()
        val cursor = timeline.maxOfOrNull { it.gsId }
        respondJsonList(ex, json.encodeToString(timeline), cursor)
    }

    private fun serveStateDiff(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)

        // Shortcut: ?last=N diffs from N snapshots back to the latest
        val last = params["last"]?.toIntOrNull()
        if (last != null) {
            val timeline = GameStateCollector.timeline()
            if (timeline.size < 2) {
                respond(ex, 404, "text/plain", "Need at least 2 snapshots for ?last diff")
                return
            }
            val toIdx = timeline.size - 1
            val fromIdx = (toIdx - last).coerceAtLeast(0)
            val diff = GameStateCollector.diff(timeline[fromIdx].gsId, timeline[toIdx].gsId)
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
        val diff = GameStateCollector.diff(from, to)
        if (diff == null) {
            respond(ex, 404, "text/plain", "Snapshot not found for gsId=$from or gsId=$to")
            return
        }
        respondJson(ex, json.encodeToString(diff))
    }

    private fun servePriorityEvents(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val events = GameStateCollector.events(since)
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
        val history = GameStateCollector.instanceHistory(id)
        respondJsonList(ex, json.encodeToString(history), null)
    }

    private fun serveRecordings(ex: HttpExchange) {
        val sessions = RecordingInspector.listSessions()
        respondJsonList(ex, json.encodeToString(sessions), null)
    }

    private fun serveRecordingSummary(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val summary = RecordingInspector.summary(id)
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
        val actions = RecordingInspector.actions(id, cardFilter = card, actorFilter = actor, limit = limit)
        respondJsonList(ex, json.encodeToString(actions), null)
    }

    private fun serveRecordingMessages(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val id = params["id"]
        if (id.isNullOrBlank()) {
            respond(ex, 400, "text/plain", "Required: ?id=<sessionId>")
            return
        }
        val messages = RecordingInspector.messages(id)
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
        val diff = RecordingInspector.compare(left, right)
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
        val recordingDir = RecordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        // Resolve session root: recording dir may be a leaf (engine/, capture/payloads/)
        // but analysis.json lives at the session root (parent of engine/ or grandparent of capture/payloads/)
        val sessionDir = RecordingInspector.resolveSessionRoot(recordingDir)
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
        val recordingDir = RecordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        val sessionDir = RecordingInspector.resolveSessionRoot(recordingDir)
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
                        val seqOk = sinceSeq <= 0 || run {
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
        val recordingDir = RecordingInspector.resolveSessionDir(id)
        if (recordingDir == null) {
            respond(ex, 404, "text/plain", "Session not found")
            return
        }
        val sessionDir = RecordingInspector.resolveSessionRoot(recordingDir)
        val analysis = SessionAnalyzer.readAnalysis(sessionDir)
        if (analysis == null) {
            respondJsonList(ex, "[]", null)
            return
        }
        respondJsonList(ex, json.encodeToString(analysis.invariantViolations), null)
    }

    private fun serveRecordingMechanics(ex: HttpExchange) {
        val manifest = SessionAnalyzer.readManifest()
        respondJson(ex, json.encodeToString(manifest.sorted()))
    }

    // --- Client error watcher ---

    private fun serveClientErrors(ex: HttpExchange) {
        val watcher = PlayerLogWatcher.active
        if (watcher == null) {
            respondJsonList(ex, "[]", null)
            return
        }
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        var errors = watcher.snapshot(since)

        // Optional filters
        val typeFilter = params["type"]
        if (typeFilter != null) {
            errors = errors.filter { it.exceptionType.contains(typeFilter, ignoreCase = true) }
        }

        val cursor = errors.maxOfOrNull { it.seq }
        respondJsonList(ex, json.encodeToString(errors), cursor)
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
                respond(ex, 500, "text/plain", "Internal error: ${t.message}")
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

        DebugEventBus.addListener(listener)
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
            DebugEventBus.removeListener(listener)
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
