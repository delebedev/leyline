package leyline.frontdoor.wire

import io.netty.channel.ChannelHandlerContext
import leyline.debug.FdDebugCollector
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
import java.util.UUID

/**
 * Netty write utilities for Front Door responses.
 *
 * Handles framing, channel writes, and [FdDebugCollector] recording.
 * Extracted from handler code so handlers stay protocol-agnostic.
 */
class FdResponseWriter {

    fun sendJson(ctx: ChannelHandlerContext, txId: String?, json: String) {
        val id = txId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeResponse(id, json)
        sendRaw(ctx, id, json.take(80), envelope)
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
        ack[1] = ClientFrameDecoder.TYPE_CTRL_ACK
        val buf = ctx.alloc().buffer(ack.size)
        buf.writeBytes(ack)
        ctx.writeAndFlush(buf)
    }

    private fun sendRaw(
        ctx: ChannelHandlerContext,
        txId: String,
        logPayload: String?,
        envelope: ByteArray,
    ) {
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)
        FdDebugCollector.record(
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
