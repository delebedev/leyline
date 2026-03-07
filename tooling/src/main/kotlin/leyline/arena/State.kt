package leyline.arena

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object State {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private fun fetch(path: String): String? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8090$path"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body() else null
    } catch (_: Exception) {
        null
    }

    fun run(args: List<String>) {
        val json = "--json" in args || args.isEmpty() // default json

        val state = fetch("/api/state")
        if (state == null) {
            if (json) {
                println("""{"source":"unavailable"}""")
            } else {
                println("Debug API unavailable (is the server running?)")
            }
            return
        }

        if (json) {
            println(state)
        } else {
            // simple human-readable
            println(state)
        }
    }

    fun errors(args: List<String>) {
        val body = fetch("/api/client-errors")
        if (body == null) {
            println("[]")
            return
        }
        println(body)
    }

    /** Poll /api/state for a field match. Returns true when matched. */
    fun pollState(condition: String, timeoutMs: Long): Boolean {
        val (field, value) = condition.split("=", limit = 2)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val body = fetch("/api/state") ?: run {
                Thread.sleep(200)
                continue
            }
            // simple substring match on JSON — works for phase=MAIN1, turn=3 etc.
            if (body.contains("\"$field\"") && body.contains(value, ignoreCase = true)) {
                return true
            }
            Thread.sleep(200)
        }
        return false
    }
}
