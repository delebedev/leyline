package leyline.account

import io.ktor.http.*
import io.ktor.server.response.*

/**
 * Send a WAS-shaped error response: `{code, grpcCode, error}`.
 * Matches the real Wizards server error format (from proxy recordings).
 */
suspend fun io.ktor.server.application.ApplicationCall.respondError(
    code: Int,
    grpcCode: String,
    error: String,
) {
    respondText(
        """{"code":$code,"grpcCode":"$grpcCode","error":"$error"}""",
        ContentType.Application.Json,
        HttpStatusCode.fromValue(code),
    )
}
