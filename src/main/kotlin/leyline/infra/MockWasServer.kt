package leyline.infra

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

/**
 * Mock Wizards Account System (WAS) -- HTTPS server that returns crafted JWTs
 * with debug roles so the MTGA client enables its built-in developer tooling.
 *
 * Endpoints:
 *   POST /oauth/token          -> LoginResponse JSON (contains access_token JWT)
 *   GET  /api/profile/me/game  -> Profile JSON
 *   POST /api/doorbell/...     -> DoorbellRingResponseV2 (FdURI + BundleManifests)
 *
 * Default port: 9443. Point client via services.conf accountSystemBaseUri + doorbellUri.
 */
class MockWasServer(
    private val port: Int = 9443,
    private val roles: List<String> = DEFAULT_ROLES,
    private val certFile: File? = null,
    private val keyFile: File? = null,
    private val fdHost: String = "localhost:30010",
) {
    private val log = LoggerFactory.getLogger(MockWasServer::class.java)
    private var server: HttpsServer? = null

    fun start() {
        val ssl = buildSslContext(certFile, keyFile)
        val srv = HttpsServer.create(InetSocketAddress(port), 0)
        srv.httpsConfigurator = HttpsConfigurator(ssl)
        srv.createContext("/auth/oauth/token") { ex -> safeHandle(ex) { handleLogin(ex) } }
        srv.createContext("/api/profile") { ex -> safeHandle(ex) { handleProfile(ex) } }
        srv.createContext("/api/doorbell") { ex -> safeHandle(ex) { handleDoorbell(ex) } }
        srv.createContext("/") { ex -> safeHandle(ex) { handleFallback(ex) } }
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "mock-was").apply { isDaemon = true }
        }
        srv.start()
        server = srv
        log.info("Mock WAS: https://localhost:{} (roles: {})", port, roles)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // -- Handlers --

    private fun handleLogin(ex: HttpExchange) {
        ex.requestBody.readBytes()
        val accountId = UUID.randomUUID().toString()
        val personaId = UUID.randomUUID().toString()
        val accessToken = buildJwt(accountId, personaId, roles)
        val refreshToken = UUID.randomUUID().toString()
        val json = buildJsonObject {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_in", 86400)
            put("token_type", "Bearer")
            put("client_id", "leyline")
            put("game_id", "MTGA")
            put("domain_id", "mtga")
            put("persona_id", personaId)
            put("account_id", accountId)
            put("display_name", "ForgePlayer")
        }.toString()
        log.info("Mock WAS: login -> account={} roles={}", accountId.take(8), roles)
        respond(ex, 200, json)
    }

    private fun handleProfile(ex: HttpExchange) {
        val json = buildJsonObject {
            put("accountID", "forge-account-1")
            put("personaID", "forge-persona-1")
            put("displayName", "ForgePlayer")
            put("gameID", "MTGA")
            put("externalID", "")
            put("email", "forge@localhost")
            put("dataOptIn", false)
            putJsonObject("presenceSettings") { put("socialMode", "PUBLIC") }
            put("countryCode", "US")
        }.toString()
        log.debug("Mock WAS: profile request")
        respond(ex, 200, json)
    }

    private fun handleDoorbell(ex: HttpExchange) {
        if (ex.requestMethod == "POST") ex.requestBody.readBytes()
        val json = buildJsonObject {
            put("FdURI", fdHost)
            putJsonArray("BundleManifests") {}
        }.toString()
        log.info("Mock WAS: doorbell -> FdURI={}", fdHost)
        respond(ex, 200, json)
    }

    private fun handleFallback(ex: HttpExchange) {
        log.warn("Mock WAS: unhandled {} {}", ex.requestMethod, ex.requestURI.path)
        if (ex.requestMethod == "POST") ex.requestBody.readBytes()
        respond(ex, 200, "{}")
    }

    // -- Helpers --

    private fun safeHandle(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.error("Mock WAS error on {}: {}", ex.requestURI, t.message, t)
            try {
                respond(ex, 500, "{\"error\":\"internal\"}")
            } catch (_: Throwable) {
                try {
                    ex.close()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun respond(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        val PROD_ROLES = listOf(
            "WotC_ACCESS",
            "MTGA_FeatureToggle",
        )
        val DEBUG_ROLES = PROD_ROLES + "MTGA_DEBUG"
        val DEFAULT_ROLES = PROD_ROLES

        fun buildJwt(accountId: String, personaId: String, roles: List<String>): String {
            val header = buildJsonObject {
                put("alg", "none")
                put("typ", "JWT")
            }.toString()
            val now = System.currentTimeMillis() / 1000
            val payload = buildJsonObject {
                put("sub", accountId)
                put("persona_id", personaId)
                put("game_id", "MTGA")
                put("iss", "leyline")
                put("iat", now)
                put("exp", now + 86400)
                putJsonArray("wotc-rols") { roles.forEach { add(JsonPrimitive(it)) } }
                put("wotc-flgs", 3)
            }.toString()
            val enc = Base64.getUrlEncoder().withoutPadding()
            return enc.encodeToString(header.toByteArray(Charsets.UTF_8)) +
                "." + enc.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "."
        }

        private fun buildSslContext(certFile: File?, keyFile: File?): SSLContext =
            TlsHelper.buildJdkSslContext(certFile, keyFile)
    }
}
