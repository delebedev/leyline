package leyline.copilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

internal enum class NativeSubmitOutcome {
    SUBMITTED,
    SUPERSEDED,
    NOT_READY,
    IDENTITY_ERROR,
    INVOKE_ERROR,
    TRANSPORT_ERROR,
}

internal data class NativeSubmitResult(
    val outcome: NativeSubmitOutcome,
    val detail: String,
)

/** Waits for one exact client request, then submits one response through it. */
internal class CopilotNativeTransport(
    bridgeUrl: String,
    private val readyPollMs: Long,
    private val maxReadyChecks: Int,
) {
    private val bridgeUrl = bridgeUrl.trimEnd('/')

    fun submit(
        prompt: GREToClientMessage,
        responseHex: String,
        superseded: () -> Boolean,
    ): NativeSubmitResult {
        val family = prompt.type.name.substringBefore('_')
        val identity =
            """{"family":"$family","gameStateId":${prompt.gameStateId},"respId":${prompt.msgId}}"""
        var networkFailures = 0
        repeat(maxReadyChecks) {
            if (superseded()) return NativeSubmitResult(NativeSubmitOutcome.SUPERSEDED, "prompt advanced")
            val readiness = post("/respond/native/status", identity)
            if (readiness == null) {
                networkFailures++
                if (networkFailures >= MAX_NETWORK_FAILURES) {
                    return NativeSubmitResult(NativeSubmitOutcome.TRANSPORT_ERROR, "readiness unavailable")
                }
                pause()
                return@repeat
            }
            when (statusOf(readiness)) {
                "ready" -> {
                    val body =
                        """{"hex":"$responseHex","family":"$family","gameStateId":${prompt.gameStateId},"respId":${prompt.msgId}}"""
                    val submitted =
                        post("/respond/native", body)
                            ?: return NativeSubmitResult(
                                NativeSubmitOutcome.TRANSPORT_ERROR,
                                "submission result unavailable",
                            )
                    classifySubmission(submitted)?.let { return it }
                }
                "no_request" -> Unit
                "stale", "not_expected" ->
                    if (!isOlderSameFamily(readiness, family, prompt)) {
                        return if (superseded()) {
                            NativeSubmitResult(NativeSubmitOutcome.SUPERSEDED, readiness)
                        } else {
                            NativeSubmitResult(NativeSubmitOutcome.IDENTITY_ERROR, readiness)
                        }
                    }
                else -> return NativeSubmitResult(NativeSubmitOutcome.INVOKE_ERROR, readiness)
            }
            pause()
        }
        return NativeSubmitResult(NativeSubmitOutcome.NOT_READY, "exact request did not bind")
    }

    private fun classifySubmission(body: String): NativeSubmitResult? =
        when (statusOf(body)) {
            "submitted" -> NativeSubmitResult(NativeSubmitOutcome.SUBMITTED, body)
            "no_request" -> null // request changed between readiness and submit; re-observe
            "stale", "not_expected" -> NativeSubmitResult(NativeSubmitOutcome.IDENTITY_ERROR, body)
            else -> NativeSubmitResult(NativeSubmitOutcome.INVOKE_ERROR, body)
        }

    private fun pause() {
        if (readyPollMs > 0) Thread.sleep(readyPollMs)
    }

    private fun post(
        path: String,
        body: String,
    ): String? =
        runCatching {
            val conn =
                (URI("$bridgeUrl$path").toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3_000
                    readTimeout = 6_000
                    setRequestProperty("Content-Type", "application/json")
                }
            conn.outputStream.use { output: OutputStream -> output.write(body.toByteArray()) }
            val input = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            input?.use { stream: InputStream -> stream.readBytes().decodeToString() }
        }.getOrNull()

    private fun statusOf(body: String): String? =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["status"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    /** An immediately preceding request may still be retiring on the client main thread. */
    private fun isOlderSameFamily(
        body: String,
        family: String,
        expected: GREToClientMessage,
    ): Boolean =
        runCatching {
            val observed = Json.parseToJsonElement(body).jsonObject["observed"]?.jsonObject ?: return false
            val observedFamily = observed["family"]?.jsonPrimitive?.content
            val observedGsId = observed["gameStateId"]?.jsonPrimitive?.intOrNull ?: return false
            val observedMsgId = observed["msgId"]?.jsonPrimitive?.intOrNull ?: return false
            observedFamily == family &&
                (
                    observedGsId < expected.gameStateId ||
                        (observedGsId == expected.gameStateId && observedMsgId < expected.msgId)
                )
        }.getOrDefault(false)

    private companion object {
        const val MAX_NETWORK_FAILURES = 3
    }
}
