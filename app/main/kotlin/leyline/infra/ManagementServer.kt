package leyline.infra

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Lightweight management HTTP server — always starts, separate from debug panel.
 *
 * Endpoints:
 *   GET /health  → 200 if server channels are active, 503 otherwise
 *
 * Intended for Docker HEALTHCHECK, load balancers, and uptime monitors.
 * Runs on its own port (default 8091) with a minimal fixed thread pool.
 */
class ManagementServer(
    private val port: Int = 8091,
    /** Probe function: returns true if the data plane is healthy. */
    private val healthCheck: () -> Boolean = { true },
) {
    private val log = LoggerFactory.getLogger(ManagementServer::class.java)
    private var server: HttpServer? = null

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/health") { ex -> serveHealth(ex) }
        srv.executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "mgmt-http").apply { isDaemon = true }
        }
        srv.start()
        server = srv
        log.info("Management server: http://localhost:{}/health", port)
    }

    fun stop() {
        server?.stop(1)
        server = null
    }

    private fun serveHealth(ex: HttpExchange) {
        try {
            val healthy = healthCheck()
            val code = if (healthy) 200 else 503
            val body = if (healthy) """{"status":"ok"}""" else """{"status":"unavailable"}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (t: Throwable) {
            log.error("Health check error: {}", t.message)
            try {
                ex.sendResponseHeaders(500, -1)
                ex.close()
            } catch (_: Throwable) {}
        }
    }
}
