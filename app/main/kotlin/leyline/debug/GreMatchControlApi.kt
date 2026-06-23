package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** Server-to-server GRE match lifecycle control API. */
class GreMatchControlApi(
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry?,
    private val controlToken: String?,
    private val resolvePuzzleReference: (String) -> String?,
) {
    private val log = LoggerFactory.getLogger(GreMatchControlApi::class.java)
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
        }

    fun mount(server: HttpServer) {
        server.createContext("/api/gre/matches") { ex ->
            try {
                if (!authorize(ex)) return@createContext
                when (ex.requestMethod) {
                    "GET" -> serveGetMatchConfig(ex)
                    "POST" -> serveGreMatchLaunch(ex)
                    "DELETE" -> serveDeleteMatchConfig(ex)
                    else -> {
                        ex.sendResponseHeaders(405, -1)
                        ex.close()
                    }
                }
            } catch (t: Throwable) {
                log.error("/api/gre/matches error: {}", t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun authorize(ex: HttpExchange): Boolean {
        val expected = controlToken?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val actual =
            ex.requestHeaders
                .getFirst("Authorization")
                ?.trim()
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotEmpty() }
        if (actual != null && actual.constantTimeEquals(expected)) return true
        respond(ex, 401, "text/plain", "Unauthorized")
        return false
    }

    private fun String.constantTimeEquals(other: String): Boolean =
        MessageDigest.isEqual(toByteArray(Charsets.UTF_8), other.toByteArray(Charsets.UTF_8))

    private fun serveGetMatchConfig(ex: HttpExchange) {
        val matchId = queryParam(ex, "matchId")?.trim()
        if (matchId.isNullOrEmpty()) {
            respond(ex, 400, "text/plain", "matchId is required")
            return
        }
        respondJson(ex, json.encodeToString(RuntimeMatchConfig.serializer().nullable, runtimeMatchConfigs?.get(matchId)))
    }

    private fun serveGreMatchLaunch(ex: HttpExchange) {
        val config = readMatchConfig(ex) ?: return
        val response =
            runtimeMatchConfigs?.configure(config) ?: run {
                respond(ex, 503, "text/plain", "Runtime match config registry unavailable")
                return
            }
        respond(ex, 200, "application/json", json.encodeToString(response))
    }

    private fun readMatchConfig(ex: HttpExchange): RuntimeMatchConfig? {
        val body =
            ex.requestBody
                .bufferedReader()
                .readText()
                .trim()
        if (body.isEmpty()) {
            respond(ex, 400, "text/plain", "Body is required")
            return null
        }

        val request = json.decodeFromString<RuntimeMatchConfig>(body)
        if (request.matchId.isBlank()) {
            respond(ex, 400, "text/plain", "matchId is required")
            return null
        }
        val puzzlePath =
            request.puzzle
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { puzzleRef ->
                    resolvePuzzleReference(puzzleRef) ?: run {
                        respond(ex, 404, "text/plain", "Puzzle not found: $puzzleRef")
                        return null
                    }
                }
        return request.copy(puzzle = puzzlePath)
    }

    private fun serveDeleteMatchConfig(ex: HttpExchange) {
        val matchId = queryParam(ex, "matchId")?.trim()
        if (matchId.isNullOrEmpty()) {
            respond(ex, 400, "text/plain", "matchId is required")
            return
        }
        runtimeMatchConfigs?.remove(matchId)
        respond(ex, 200, "text/plain", "Runtime match config cleared")
    }

    private fun queryParam(
        ex: HttpExchange,
        key: String,
    ): String? =
        ex.requestURI.query
            ?.split("&")
            ?.firstNotNullOfOrNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.firstOrNull() == key) parts.getOrNull(1) ?: "" else null
            }

    private fun respond(
        ex: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondJson(
        ex: HttpExchange,
        body: String,
    ) = respond(ex, 200, "application/json; charset=utf-8", body)
}
