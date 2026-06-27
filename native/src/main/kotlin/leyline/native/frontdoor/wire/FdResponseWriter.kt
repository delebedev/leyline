package leyline.native.frontdoor.wire

import io.netty.channel.ChannelHandlerContext
import java.util.UUID

/**
 * Netty write utilities for Front Door responses.
 *
 * Handles framing and channel writes.
 * Extracted from handler code so handlers stay protocol-agnostic.
 */
class FdResponseWriter {
    /** Acknowledge a control init frame by echoing it with the ACK type byte. */
    fun sendCtrlAck(
        ctx: ChannelHandlerContext,
        initFrame: ByteArray,
    ) {
        val ack = initFrame.copyOf()
        ack[1] = FdWireConstants.TYPE_CTRL_ACK
        val buf = ctx.alloc().buffer(ack.size)
        buf.writeBytes(ack)
        ctx.writeAndFlush(buf)
    }

    /** Send a Front Door response through the single response model. */
    fun send(
        ctx: ChannelHandlerContext,
        txId: String?,
        response: FdResponse,
    ) {
        val id = txId ?: UUID.randomUUID().toString()
        val envelope =
            when (response) {
                is FdResponse.Json -> FdEnvelope.encodeResponse(id, response.payload)
                is FdResponse.RawProto -> FdEnvelope.encodeRawProtoResponse(id, response.bytes)
                is FdResponse.TypedProto -> FdEnvelope.encodeProtoResponse(id, "type.googleapis.com/${response.typeName}")
                is FdResponse.Empty -> FdEnvelope.encodeEmptyResponse(id)
            }
        sendRaw(ctx, envelope)
    }

    private fun sendRaw(
        ctx: ChannelHandlerContext,
        envelope: ByteArray,
    ) {
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)
        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }
}
