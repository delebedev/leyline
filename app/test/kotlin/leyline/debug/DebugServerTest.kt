package leyline.debug

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class DebugServerTest :
    FunSpec({
        test("mounts only current diagnostic and puzzle routes") {
            val port = ServerSocket(0).use { it.localPort }
            val server = DebugServer(port = port, runtimePuzzle = AtomicReference(null))
            server.start()
            try {
                assertSoftly {
                    request(port, "GET", "/api/puzzle") shouldBe 200
                    request(port, "GET", "/api/best-play") shouldBe 200
                    request(port, "GET", "/api/inject-full") shouldBe 405

                    listOf(
                        "/",
                        "/api/events",
                        "/api/priority-log",
                        "/api/gre/matches",
                        "/api/draft/status",
                    ).forEach { path ->
                        request(port, "GET", path) shouldBe 404
                    }
                }
            } finally {
                server.stop()
            }
        }
    })

private fun request(
    port: Int,
    method: String,
    path: String,
): Int {
    val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    return try {
        connection.responseCode
    } finally {
        connection.disconnect()
    }
}
