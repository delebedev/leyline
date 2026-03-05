package leyline.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.UnitTag
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MockWasServerTest :
    FunSpec({

        tags(UnitTag)

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        val client = HttpClient.newBuilder().sslContext(sslCtx).build()

        fun get(port: Int, path: String): HttpResponse<String> =
            client.send(
                HttpRequest.newBuilder().uri(URI.create("https://localhost:$port$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun post(port: Int, path: String, body: String = ""): HttpResponse<String> =
            client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:$port$path"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        // -- Mock mode tests --

        test("mock login returns JWT with expected fields") {
            val was = MockWasServer(port = 19443, fdHost = "localhost:30010")
            was.start()
            try {
                val resp = post(19443, "/auth/oauth/token", "grant_type=client_credentials")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["token_type"]?.jsonPrimitive?.content shouldBe "Bearer"
                json["access_token"]?.jsonPrimitive?.content shouldContain "."
                json["display_name"]?.jsonPrimitive?.content shouldBe "ForgePlayer"
            } finally {
                was.stop()
            }
        }

        test("mock login with debug roles includes MTGA_DEBUG") {
            val was = MockWasServer(port = 19444, roles = MockWasServer.DEBUG_ROLES, fdHost = "localhost:30010")
            was.start()
            try {
                val resp = post(19444, "/auth/oauth/token")
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                val jwt = json["access_token"]!!.jsonPrimitive.content
                val payload = String(java.util.Base64.getUrlDecoder().decode(jwt.split(".")[1]))
                payload shouldContain "MTGA_DEBUG"
            } finally {
                was.stop()
            }
        }

        test("mock profile returns expected shape") {
            val was = MockWasServer(port = 19445, fdHost = "localhost:30010")
            was.start()
            try {
                val resp = get(19445, "/api/profile/me/game")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["displayName"]?.jsonPrimitive?.content shouldBe "ForgePlayer"
                json["gameID"]?.jsonPrimitive?.content shouldBe "MTGA"
            } finally {
                was.stop()
            }
        }

        test("mock doorbell returns configured FdURI") {
            val was = MockWasServer(port = 19446, fdHost = "myhost:9999")
            was.start()
            try {
                val resp = post(19446, "/api/doorbell/api/v2/ring")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["FdURI"]?.jsonPrimitive?.content shouldBe "myhost:9999"
            } finally {
                was.stop()
            }
        }

        test("mock fallback returns empty JSON") {
            val was = MockWasServer(port = 19447, fdHost = "localhost:30010")
            was.start()
            try {
                val resp = get(19447, "/unknown/path")
                resp.statusCode() shouldBe 200
                resp.body() shouldBe "{}"
            } finally {
                was.stop()
            }
        }

        test("isProxy is false in mock mode") {
            val was = MockWasServer(port = 19448, fdHost = "localhost:30010")
            was.isProxy shouldBe false
        }

        // -- Proxy mode tests --

        test("isProxy is true when upstreamWas is set") {
            val was = MockWasServer(
                port = 19449,
                fdHost = "localhost:30010",
                upstreamWas = "https://example.com",
            )
            was.isProxy shouldBe true
        }

        test("proxy login forwards to upstream") {
            val fakeUpstream = FakeUpstreamServer(19460)
            fakeUpstream.start()
            val was = MockWasServer(
                port = 19450,
                fdHost = "localhost:30010",
                upstreamWas = "https://localhost:19460",
            )
            was.start()
            try {
                val resp = post(19450, "/auth/oauth/token", "grant_type=client_credentials")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["upstream"]?.jsonPrimitive?.content shouldBe "true"
                json["path"]?.jsonPrimitive?.content shouldBe "/auth/oauth/token"
            } finally {
                was.stop()
                fakeUpstream.stop()
            }
        }

        test("proxy doorbell rewrites FdURI to local fdHost") {
            val fakeUpstream = FakeUpstreamServer(19461, doorbellFdUri = "real-server.wizards.com:30010")
            fakeUpstream.start()
            val was = MockWasServer(
                port = 19451,
                fdHost = "localhost:30010",
                upstreamWas = "https://localhost:19461",
                upstreamDoorbell = "https://localhost:19461",
            )
            was.start()
            try {
                val resp = post(19451, "/api/doorbell/api/v2/ring")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["FdURI"]?.jsonPrimitive?.content shouldBe "localhost:30010"
            } finally {
                was.stop()
                fakeUpstream.stop()
            }
        }

        test("proxy profile forwards to upstream") {
            val fakeUpstream = FakeUpstreamServer(19462)
            fakeUpstream.start()
            val was = MockWasServer(
                port = 19452,
                fdHost = "localhost:30010",
                upstreamWas = "https://localhost:19462",
            )
            was.start()
            try {
                val resp = get(19452, "/api/profile/me/game")
                resp.statusCode() shouldBe 200
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                json["path"]?.jsonPrimitive?.content shouldBe "/api/profile/me/game"
            } finally {
                was.stop()
                fakeUpstream.stop()
            }
        }
    })

/**
 * Minimal HTTPS server that echoes request path back as JSON.
 * Used as a fake upstream WAS for proxy tests.
 */
private class FakeUpstreamServer(
    private val port: Int,
    private val doorbellFdUri: String = "real-server:30010",
) {
    private var server: com.sun.net.httpserver.HttpsServer? = null

    fun start() {
        val ssl = TlsHelper.buildJdkSslContext(null, null)
        val srv = com.sun.net.httpserver.HttpsServer.create(java.net.InetSocketAddress(port), 0)
        srv.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(ssl)
        srv.createContext("/api/doorbell") { ex ->
            if (ex.requestMethod == "POST") ex.requestBody.readBytes()
            val json = """{"FdURI":"$doorbellFdUri","BundleManifests":[]}"""
            val bytes = json.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.createContext("/") { ex ->
            if (ex.requestMethod == "POST") ex.requestBody.readBytes()
            val json = """{"upstream":"true","path":"${ex.requestURI.path}"}"""
            val bytes = json.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "fake-upstream").apply { isDaemon = true }
        }
        srv.start()
        server = srv
    }

    fun stop() {
        server?.stop(0)
    }
}
