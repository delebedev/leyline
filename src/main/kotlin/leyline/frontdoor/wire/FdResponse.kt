package leyline.frontdoor.wire

/**
 * Sealed response type for Front Door handlers.
 *
 * Handlers return an [FdResponse] instead of calling [FdResponseWriter] methods directly.
 * This decouples handlers from Netty and makes responses easy to assert in tests.
 */
sealed class FdResponse {
    /** JSON payload in Response envelope field 3. */
    data class Json(val payload: String) : FdResponse()

    /** Pre-built protobuf bytes in Response envelope field 2 (golden captures). */
    data class RawProto(val bytes: ByteArray) : FdResponse()

    /** Empty protobuf Any with type URL in Response envelope field 2. */
    data class TypedProto(val typeName: String) : FdResponse()

    /** Response with only transactionId, no payload. */
    data object Empty : FdResponse()
}
