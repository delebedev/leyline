package forge.nexus.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * Embedded HTTP server for the nexus debug panel.
 * Zero-dep JDK [HttpServer] on the given port (default 8090).
 *
 * Endpoints:
 * - `GET /`               → nexus-debug.html from classpath
 * - `GET /api/messages`   → JSON array of entries (supports `?since=N`)
 * - `GET /api/state`      → match state snapshot
 * - `GET /api/id-map`     → instanceId cross-reference table
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
        srv.executor = null // default single-thread executor
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
        val entries = ArenaDebugCollector.snapshot(since)
        respondJson(ex, json.encodeToString(entries))
    }

    private fun serveState(ex: HttpExchange) {
        val state = ArenaDebugCollector.matchState()
        respondJson(ex, json.encodeToString(state))
    }

    private fun serveIdMap(ex: HttpExchange) {
        val map = ArenaDebugCollector.idMap()
        respondJson(ex, json.encodeToString(map))
    }

    private fun serveLogs(ex: HttpExchange) {
        val params = parseQuery(ex.requestURI.rawQuery)
        val since = params["since"]?.toIntOrNull() ?: 0
        val level = params["level"] ?: "DEBUG"
        val logs = ArenaDebugCollector.logSnapshot(since, level)
        respondJson(ex, json.encodeToString(logs))
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

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) part.substring(0, eq) to part.substring(eq + 1) else null
        }.toMap()
    }
}
