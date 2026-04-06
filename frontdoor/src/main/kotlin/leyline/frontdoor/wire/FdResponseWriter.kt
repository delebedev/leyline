package leyline.frontdoor.wire

import io.netty.channel.ChannelHandlerContext
import java.util.UUID

/**
 * Netty write utilities for Front Door responses.
 *
 * Handles framing and channel writes.
 * Extracted from handler code so handlers stay protocol-agnostic.
 */
class FdResponseWriter {

    fun sendJson(ctx: ChannelHandlerContext, txId: String?, json: String) {
        val id = txId ?: UUID.randomUUID().toString()
        sendRaw(ctx, FdEnvelope.encodeResponse(id, json))
    }

    /** Send a Response with only transactionId, no payload. */
    fun sendEmpty(ctx: ChannelHandlerContext, txId: String?) {
        val id = txId ?: UUID.randomUUID().toString()
        sendRaw(ctx, FdEnvelope.encodeEmptyResponse(id))
    }

    /** Send a Response with an empty protobuf Any in field 2 (default/empty proto message). */
    fun sendProto(ctx: ChannelHandlerContext, txId: String?, typeName: String) {
        val id = txId ?: UUID.randomUUID().toString()
        val typeUrl = "type.googleapis.com/$typeName"
        sendRaw(ctx, FdEnvelope.encodeProtoResponse(id, typeUrl))
    }

    /** Send a Response with raw protobuf bytes in field 2. */
    fun sendRawProto(ctx: ChannelHandlerContext, txId: String?, protoPayload: ByteArray) {
        val id = txId ?: UUID.randomUUID().toString()
        sendRaw(ctx, FdEnvelope.encodeRawProtoResponse(id, protoPayload))
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
        val envelope = when (response) {
            is FdResponse.Json -> FdEnvelope.encodeResponse(id, response.payload)
            is FdResponse.RawProto -> FdEnvelope.encodeRawProtoResponse(id, response.bytes)
            is FdResponse.TypedProto -> FdEnvelope.encodeProtoResponse(id, "type.googleapis.com/${response.typeName}")
            is FdResponse.Empty -> FdEnvelope.encodeEmptyResponse(id)
        }
        sendRaw(ctx, envelope)
    }

    /** Send a Cmd push notification (S→C, not a response to a request). */
    fun sendPush(ctx: ChannelHandlerContext, cmdType: CmdType, json: String) {
        val txId = UUID.randomUUID().toString()
        sendRaw(ctx, FdEnvelope.encodeCmd(cmdType.value, txId, json))
    }

    private fun sendRaw(ctx: ChannelHandlerContext, envelope: ByteArray) {
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)
        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }
}
