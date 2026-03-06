package leyline.account

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val log = LoggerFactory.getLogger("leyline.account.proxy")

private val proxyClient: HttpClient by lazy {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    HttpClient.newBuilder().sslContext(sslCtx).build()
}

/**
 * Proxy routes — forward all requests to upstream Wizards servers.
 * Doorbell responses get FdURI rewritten to local [fdHost].
 */
fun Route.proxyRoutes(upstreamWas: String, upstreamDoorbell: String, fdHost: String) {
    post("/api/doorbell/{...}") {
        val subPath = call.request.path().removePrefix("/api/doorbell")
        proxyDoorbell(call, "$upstreamDoorbell$subPath", fdHost)
    }
    route("{...}") {
        handle {
            val path = call.request.path()
            proxyPass(call, "$upstreamWas$path")
        }
    }
}

private suspend fun proxyPass(call: io.ktor.server.application.ApplicationCall, targetUrl: String) {
    val body = if (call.request.httpMethod == HttpMethod.Post) call.receiveText() else null
    val reqBuilder = HttpRequest.newBuilder().uri(URI.create(targetUrl))
    forwardHeaders(call, reqBuilder)
    if (body != null) {
        reqBuilder.method(call.request.httpMethod.value, HttpRequest.BodyPublishers.ofString(body))
    } else {
        reqBuilder.method(call.request.httpMethod.value, HttpRequest.BodyPublishers.noBody())
    }
    val resp = proxyClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
    log.info("Proxy: {} {} -> {} ({})", call.request.httpMethod.value, call.request.path(), targetUrl, resp.statusCode())
    val contentType = resp.headers().firstValue("Content-Type").orElse("application/json")
    call.respondBytes(
        resp.body(),
        ContentType.parse(contentType),
        HttpStatusCode.fromValue(resp.statusCode()),
    )
}

private suspend fun proxyDoorbell(call: io.ktor.server.application.ApplicationCall, targetUrl: String, fdHost: String) {
    val body = if (call.request.httpMethod == HttpMethod.Post) call.receiveText() else null
    val reqBuilder = HttpRequest.newBuilder().uri(URI.create(targetUrl))
    forwardHeaders(call, reqBuilder)
    if (body != null) {
        reqBuilder.method(call.request.httpMethod.value, HttpRequest.BodyPublishers.ofString(body))
    } else {
        reqBuilder.method(call.request.httpMethod.value, HttpRequest.BodyPublishers.noBody())
    }
    val resp = proxyClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
    var json = resp.body()
    // Rewrite FdURI to localhost so client connects to our proxy FD
    json = json.replace(Regex(""""FdURI"\s*:\s*"[^"]+""""), """"FdURI":"$fdHost"""")
    log.info("Proxy doorbell: {} -> {} (rewrote FdURI={})", call.request.path(), targetUrl, fdHost)
    call.respondText(
        json,
        ContentType.Application.Json,
        HttpStatusCode.fromValue(resp.statusCode()),
    )
}

private fun forwardHeaders(call: io.ktor.server.application.ApplicationCall, reqBuilder: HttpRequest.Builder) {
    call.request.headers.forEach { key, values ->
        if (key.equals("Host", ignoreCase = true)) return@forEach
        if (key.equals("Content-Length", ignoreCase = true)) return@forEach
        values.forEach { v -> runCatching { reqBuilder.header(key, v) } }
    }
}
