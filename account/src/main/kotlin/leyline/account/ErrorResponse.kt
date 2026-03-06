package leyline.account

import io.ktor.http.*
import io.ktor.server.response.*

/**
 * Account server error codes — must match the real upstream server's error shapes
 * (captured from proxy recordings). gRPC status codes per google.rpc.Code.
 */
enum class AccountError(val httpCode: Int, val grpcCode: String, val message: String) {
    MISSING_FIELD(400, "3", "MISSING USERNAME"),
    MISSING_PASSWORD(400, "3", "MISSING PASSWORD"),
    MISSING_REFRESH_TOKEN(400, "3", "MISSING REFRESH TOKEN"),
    UNSUPPORTED_GRANT_TYPE(400, "3", "UNSUPPORTED GRANT TYPE"),
    INVALID_REQUEST(400, "3", "INVALID REQUEST"),
    INVALID_CREDENTIALS(401, "16", "INVALID ACCOUNT CREDENTIALS"),
    INVALID_CLIENT(401, "16", "INVALID CLIENT CREDENTIALS"),
    MISSING_AUTH(401, "16", "MISSING AUTHORIZATION"),
    INVALID_TOKEN(401, "16", "INVALID TOKEN"),
    NOT_FOUND(404, "5", "ACCOUNT NOT FOUND"),
    INVALID_EMAIL(422, "6", "INVALID EMAIL"),
}

/** Send an account error response: `{code, grpcCode, error}`. */
suspend fun io.ktor.server.application.ApplicationCall.respondError(err: AccountError) {
    respondText(
        """{"code":${err.httpCode},"grpcCode":"${err.grpcCode}","error":"${err.message}"}""",
        ContentType.Application.Json,
        HttpStatusCode.fromValue(err.httpCode),
    )
}
