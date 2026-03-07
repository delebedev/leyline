package leyline.frontdoor.wire

import io.netty.channel.ChannelHandlerContext
import java.util.UUID

/**
 * Netty write utilities for Front Door responses.
 *
 * Handles framing, channel writes, and optional message recording.
 * Extracted from handler code so handlers stay protocol-agnostic.
 */
class FdResponseWriter(
    private val onFdMessage: ((String, FdEnvelope.FdMessage) -> Unit)? = null,
) {

    fun sendJson(ctx: ChannelHandlerContext, txId: String?, json: String) {
        val id = txId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeResponse(id, json)
        sendRaw(ctx, id, json, envelope)
    }

    /** Send a Response with only transactionId, no payload. */
    fun sendEmpty(ctx: ChannelHandlerContext, txId: String?) {
        val id = txId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeEmptyResponse(id)
        sendRaw(ctx, id, null, envelope)
    }

    /** Send a Response with an empty protobuf Any in field 2 (default/empty proto message). */
    fun sendProto(ctx: ChannelHandlerContext, txId: String?, typeName: String) {
        val id = txId ?: UUID.randomUUID().toString()
        val typeUrl = "type.googleapis.com/$typeName"
        val envelope = FdEnvelope.encodeProtoResponse(id, typeUrl)
        sendRaw(ctx, id, "(proto empty $typeName)", envelope)
    }

    /** Send a Response with raw golden protobuf bytes in field 2. */
    fun sendRawProto(ctx: ChannelHandlerContext, txId: String?, protoPayload: ByteArray) {
        val id = txId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeRawProtoResponse(id, protoPayload)
        sendRaw(ctx, id, "(proto ${protoPayload.size}B)", envelope)
    }

    /** Acknowledge a control init frame by echoing it with the ACK type byte. */
    fun sendCtrlAck(ctx: ChannelHandlerContext, initFrame: ByteArray) {
        val ack = initFrame.copyOf()
        ack[1] = FdWireConstants.TYPE_CTRL_ACK
        val buf = ctx.alloc().buffer(ack.size)
        buf.writeBytes(ack)
        ctx.writeAndFlush(buf)
    }

    /** Consolidated send for [FdResponse] — preferred over the individual methods. */
    fun send(ctx: ChannelHandlerContext, txId: String?, response: FdResponse) {
        val id = txId ?: UUID.randomUUID().toString()
        val (envelope, logPayload) = when (response) {
            is FdResponse.Json -> FdEnvelope.encodeResponse(id, response.payload) to response.payload
            is FdResponse.RawProto -> FdEnvelope.encodeRawProtoResponse(id, response.bytes) to "(proto ${response.bytes.size}B)"
            is FdResponse.TypedProto -> {
                val typeUrl = "type.googleapis.com/${response.typeName}"
                FdEnvelope.encodeProtoResponse(id, typeUrl) to "(proto empty ${response.typeName})"
            }
            is FdResponse.Empty -> FdEnvelope.encodeEmptyResponse(id) to null
        }
        sendRaw(ctx, id, logPayload, envelope)
    }

    /** Send a Cmd push notification (S→C, not a response to a request). */
    fun sendPush(ctx: ChannelHandlerContext, cmdType: CmdType, json: String) {
        val txId = UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeCmd(cmdType.value, txId, json)
        sendRaw(ctx, txId, json, envelope)
    }

    private fun sendRaw(
        ctx: ChannelHandlerContext,
        txId: String,
        logPayload: String?,
        envelope: ByteArray,
    ) {
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)
        onFdMessage?.invoke(
            "S2C",
            FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = txId,
                jsonPayload = logPayload,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            ),
        )
        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }
}
