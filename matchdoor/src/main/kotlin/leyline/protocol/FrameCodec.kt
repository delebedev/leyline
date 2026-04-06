package leyline.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToMessageEncoder
import org.slf4j.LoggerFactory

/**
 * Client wire format: every message is a 6-byte header + variable-length payload.
 *
 * Header layout:
 * ```
 * Byte  0:   Version          (0x04)
 * Byte  1:   Frame type       (0x11 / 0x12 / 0x13 / 0x21)
 * Bytes 2-5: Payload length   (uint32 little-endian)
 * ```
 *
 * Frame types:
 * - `0x12` CTRL_INIT — announces a message (4-byte nonce payload)
 * - `0x13` CTRL_ACK  — acknowledges (echoes the 4-byte nonce)
 * - `0x21` Data frame (Front Door + Match Door C→S)
 * - `0x11` Data frame (Match Door S→C)
 */

/** Decodes the 6-byte header framing. Outputs a ByteBuf per message (header + payload). */
class ClientFrameDecoder : ByteToMessageDecoder() {

    private val log = LoggerFactory.getLogger(ClientFrameDecoder::class.java)

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (buf.readableBytes() < HEADER_SIZE) return

        // Peek at payload length: uint32 LE at offset 2
        val payloadLength = buf.getIntLE(buf.readerIndex() + LENGTH_OFFSET)

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            log.warn("Bad payload length {} at offset {}, skipping header", payloadLength, buf.readerIndex())
            buf.skipBytes(HEADER_SIZE)
            return
        }

        val frameLength = HEADER_SIZE + payloadLength
        if (buf.readableBytes() < frameLength) return // wait for more data

        out.add(buf.readRetainedSlice(frameLength))
    }

    companion object {
        const val HEADER_SIZE = 6

        /** Offset of the 4-byte little-endian payload length within the header. */
        const val LENGTH_OFFSET = 2
        const val MAX_PAYLOAD = 1_048_576 // 1 MB

        /** Frame type constants. */
        const val VERSION: Byte = 0x04
        const val TYPE_CTRL_INIT: Byte = 0x12
        const val TYPE_CTRL_ACK: Byte = 0x13
        const val TYPE_DATA_FD: Byte = 0x21 // Front Door data + Match Door C→S
        const val TYPE_DATA_MATCH: Byte = 0x11 // Match Door S→C
    }
}

/**
 * Strips the 6-byte header, passing only the payload downstream.
 * Control frames: CTRL_INIT is echoed back as CTRL_ACK; CTRL_ACK is dropped.
 */
class ClientHeaderStripper : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(ClientHeaderStripper::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) {
            if (msg.readableBytes() <= ClientFrameDecoder.HEADER_SIZE) {
                msg.release()
                return
            }
            val frameType = msg.getByte(msg.readerIndex() + 1)
            if (frameType == ClientFrameDecoder.TYPE_CTRL_INIT) {
                // Echo full frame back with type flipped to CTRL_ACK
                log.debug("Match Door: CTRL_INIT received, sending ACK")
                val bytes = ByteArray(msg.readableBytes())
                msg.readBytes(bytes)
                msg.release()
                bytes[1] = ClientFrameDecoder.TYPE_CTRL_ACK
                val ack = ctx.alloc().buffer(bytes.size)
                ack.writeBytes(bytes)
                ctx.writeAndFlush(ack)
                return
            }
            if (frameType == ClientFrameDecoder.TYPE_CTRL_ACK) {
                msg.release()
                return
            }
            msg.skipBytes(ClientFrameDecoder.HEADER_SIZE)
            ctx.fireChannelRead(msg)
        } else {
            ctx.fireChannelRead(msg)
        }
    }
}

/**
 * Prepends a 6-byte client header to outgoing ByteBuf payloads.
 *
 * @param frameType the frame type byte to use (default: [ClientFrameDecoder.TYPE_DATA_MATCH] for S→C)
 */
class ClientHeaderPrepender(
    private val frameType: Byte = ClientFrameDecoder.TYPE_DATA_MATCH,
) : MessageToMessageEncoder<ByteBuf>() {

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val payloadLength = msg.readableBytes()
        val frame = ctx.alloc().buffer(ClientFrameDecoder.HEADER_SIZE + payloadLength)
        frame.writeByte(ClientFrameDecoder.VERSION.toInt()) // byte 0: version
        frame.writeByte(frameType.toInt()) // byte 1: frame type
        frame.writeIntLE(payloadLength) // bytes 2-5: payload length (LE)
        frame.writeBytes(msg)
        out.add(frame)
    }
}
