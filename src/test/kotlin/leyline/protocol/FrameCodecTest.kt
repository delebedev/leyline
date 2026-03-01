package leyline.protocol

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import leyline.protocol.ClientFrameDecoder.Companion.HEADER_SIZE
import leyline.protocol.ClientFrameDecoder.Companion.TYPE_CTRL_ACK
import leyline.protocol.ClientFrameDecoder.Companion.TYPE_CTRL_INIT
import leyline.protocol.ClientFrameDecoder.Companion.TYPE_DATA_FD
import leyline.protocol.ClientFrameDecoder.Companion.TYPE_DATA_MATCH
import leyline.protocol.ClientFrameDecoder.Companion.VERSION

/**
 * Frame codec roundtrip tests — encode via [ClientHeaderPrepender], decode via
 * [ClientFrameDecoder] + [ClientHeaderStripper], verify payload survives intact.
 *
 * Also exercises CTRL_INIT/ACK echo and boundary conditions. Pure Netty
 * EmbeddedChannel, no engine or TLS.
 */
class FrameCodecTest :
    FunSpec({

        val channels = mutableListOf<EmbeddedChannel>()

        afterEach {
            channels.forEach { it.finishAndReleaseAll() }
            channels.clear()
        }

        fun encoder(type: Byte): EmbeddedChannel =
            EmbeddedChannel(ClientHeaderPrepender(type)).also { channels += it }

        fun decoder(): EmbeddedChannel =
            EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper()).also { channels += it }

        /** Roundtrip: payload → prepender → decoder+stripper → same payload bytes. */
        test("roundtrip data frame") {
            val payload = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

            val enc = encoder(TYPE_DATA_MATCH)
            enc.writeOutbound(Unpooled.wrappedBuffer(payload))
            val framed = enc.readOutbound<ByteBuf>()
                ?: error("Encoder produced no output")

            framed.readableBytes() shouldBe HEADER_SIZE + payload.size

            val dec = decoder()
            dec.writeInbound(framed)
            val decoded = dec.readInbound<ByteBuf>()
                ?: error("Decoder produced no output")

            val result = ByteArray(decoded.readableBytes())
            decoded.readBytes(result)
            decoded.release()

            result shouldBe payload
        }

        /** Empty payload roundtrips cleanly (e.g. heartbeat). */
        test("roundtrip empty payload") {
            val enc = encoder(TYPE_DATA_MATCH)
            enc.writeOutbound(Unpooled.EMPTY_BUFFER.retainedDuplicate())
            val framed = enc.readOutbound<ByteBuf>()
                ?: error("Encoder produced no output")

            framed.readableBytes() shouldBe HEADER_SIZE

            val dec = decoder()
            dec.writeInbound(framed)
            // Empty payload after header strip → ClientHeaderStripper drops it (readableBytes <= HEADER_SIZE)
            val decoded = dec.readInbound<ByteBuf>()
            decoded.shouldBeNull()
        }

        /** Header bytes: version=0x04, correct frame type, LE payload length. */
        test("header layout matches spec") {
            val payload = ByteArray(300) { (it % 256).toByte() }

            val enc = encoder(TYPE_DATA_FD)
            enc.writeOutbound(Unpooled.wrappedBuffer(payload))
            val framed = enc.readOutbound<ByteBuf>()
                ?: error("Encoder produced no output")

            framed.getByte(0) shouldBe VERSION
            framed.getByte(1) shouldBe TYPE_DATA_FD
            framed.getIntLE(2) shouldBe payload.size

            framed.release()
        }

        /** CTRL_INIT frame is echoed back as CTRL_ACK (not passed downstream). */
        test("ctrl init echoed as ack") {
            val nonce = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val initFrame = Unpooled.buffer(HEADER_SIZE + nonce.size)
            initFrame.writeByte(VERSION.toInt())
            initFrame.writeByte(TYPE_CTRL_INIT.toInt())
            initFrame.writeIntLE(nonce.size)
            initFrame.writeBytes(nonce)

            val dec = decoder()
            dec.writeInbound(initFrame)

            // Should NOT produce inbound (consumed by stripper)
            dec.readInbound<Any>().shouldBeNull()

            // Should produce outbound ACK
            val ack = dec.readOutbound<ByteBuf>()
                ?: error("CTRL_INIT should produce an ACK response")

            ack.getByte(0) shouldBe VERSION
            ack.getByte(1) shouldBe TYPE_CTRL_ACK
            ack.getIntLE(2) shouldBe nonce.size

            // Nonce echoed
            val echoedNonce = ByteArray(nonce.size)
            ack.getBytes(HEADER_SIZE, echoedNonce)
            echoedNonce shouldBe nonce

            ack.release()
        }

        /** Decoder buffers partial frames until complete. */
        test("partial frame buffered") {
            val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

            val enc = encoder(TYPE_DATA_MATCH)
            enc.writeOutbound(Unpooled.wrappedBuffer(payload))
            val framed = enc.readOutbound<ByteBuf>()
                ?: error("Encoder produced no output")

            val allBytes = ByteArray(framed.readableBytes())
            framed.readBytes(allBytes)
            framed.release()

            val dec = decoder()

            // Feed first 4 bytes (incomplete header+payload)
            dec.writeInbound(Unpooled.wrappedBuffer(allBytes, 0, 4))
            dec.readInbound<Any>().shouldBeNull()

            // Feed remaining bytes
            dec.writeInbound(Unpooled.wrappedBuffer(allBytes, 4, allBytes.size - 4))
            val decoded = dec.readInbound<ByteBuf>()
                ?: error("Complete frame should decode")

            val result = ByteArray(decoded.readableBytes())
            decoded.readBytes(result)
            decoded.release()
            result shouldBe payload
        }

        /** Large payload (near typical GSM size) roundtrips correctly. */
        test("large payload roundtrip") {
            val payload = ByteArray(65_536) { (it % 251).toByte() } // prime mod avoids repeats

            val enc = encoder(TYPE_DATA_MATCH)
            enc.writeOutbound(Unpooled.wrappedBuffer(payload))
            val framed = enc.readOutbound<ByteBuf>()
                ?: error("Encoder produced no output")

            val dec = decoder()
            dec.writeInbound(framed)
            val decoded = dec.readInbound<ByteBuf>()
                ?: error("Decoder produced no output")

            val result = ByteArray(decoded.readableBytes())
            decoded.readBytes(result)
            decoded.release()

            result.size shouldBe payload.size
            result.contentEquals(payload).shouldBeTrue()
        }
    })
