package forge.nexus.protocol

import forge.nexus.protocol.ClientFrameDecoder.Companion.HEADER_SIZE
import forge.nexus.protocol.ClientFrameDecoder.Companion.TYPE_CTRL_ACK
import forge.nexus.protocol.ClientFrameDecoder.Companion.TYPE_CTRL_INIT
import forge.nexus.protocol.ClientFrameDecoder.Companion.TYPE_DATA_FD
import forge.nexus.protocol.ClientFrameDecoder.Companion.TYPE_DATA_MATCH
import forge.nexus.protocol.ClientFrameDecoder.Companion.VERSION
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

/**
 * Frame codec roundtrip tests — encode via [ClientHeaderPrepender], decode via
 * [ClientFrameDecoder] + [ClientHeaderStripper], verify payload survives intact.
 *
 * Also exercises CTRL_INIT/ACK echo and boundary conditions. Pure Netty
 * EmbeddedChannel, no engine or TLS.
 */
@Test(groups = ["unit"])
class FrameCodecTest {

    private lateinit var encoderChannel: EmbeddedChannel
    private lateinit var decoderChannel: EmbeddedChannel

    @AfterMethod
    fun tearDown() {
        if (::encoderChannel.isInitialized) encoderChannel.finishAndReleaseAll()
        if (::decoderChannel.isInitialized) decoderChannel.finishAndReleaseAll()
    }

    /** Roundtrip: payload → prepender → decoder+stripper → same payload bytes. */
    @Test
    fun roundtripDataFrame() {
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

        encoderChannel = EmbeddedChannel(ClientHeaderPrepender(TYPE_DATA_MATCH))
        encoderChannel.writeOutbound(Unpooled.wrappedBuffer(payload))
        val framed = encoderChannel.readOutbound<ByteBuf>()
            ?: error("Encoder produced no output")

        assertEquals(framed.readableBytes(), HEADER_SIZE + payload.size)

        decoderChannel = EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper())
        decoderChannel.writeInbound(framed)
        val decoded = decoderChannel.readInbound<ByteBuf>()
            ?: error("Decoder produced no output")

        val result = ByteArray(decoded.readableBytes())
        decoded.readBytes(result)
        decoded.release()

        assertEquals(result, payload)
    }

    /** Empty payload roundtrips cleanly (e.g. heartbeat). */
    @Test
    fun roundtripEmptyPayload() {
        encoderChannel = EmbeddedChannel(ClientHeaderPrepender(TYPE_DATA_MATCH))
        encoderChannel.writeOutbound(Unpooled.EMPTY_BUFFER.retainedDuplicate())
        val framed = encoderChannel.readOutbound<ByteBuf>()
            ?: error("Encoder produced no output")

        assertEquals(framed.readableBytes(), HEADER_SIZE)

        decoderChannel = EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper())
        decoderChannel.writeInbound(framed)
        // Empty payload after header strip → ClientHeaderStripper drops it (readableBytes <= HEADER_SIZE)
        val decoded = decoderChannel.readInbound<ByteBuf>()
        assertNull(decoded, "Empty payload should be dropped by stripper")
    }

    /** Header bytes: version=0x04, correct frame type, LE payload length. */
    @Test
    fun headerLayoutMatchesSpec() {
        val payload = ByteArray(300) { (it % 256).toByte() }

        encoderChannel = EmbeddedChannel(ClientHeaderPrepender(TYPE_DATA_FD))
        encoderChannel.writeOutbound(Unpooled.wrappedBuffer(payload))
        val framed = encoderChannel.readOutbound<ByteBuf>()
            ?: error("Encoder produced no output")

        assertEquals(framed.getByte(0), VERSION, "byte 0 = version")
        assertEquals(framed.getByte(1), TYPE_DATA_FD, "byte 1 = frame type")
        assertEquals(framed.getIntLE(2), payload.size, "bytes 2-5 = LE payload length")

        framed.release()
    }

    /** CTRL_INIT frame is echoed back as CTRL_ACK (not passed downstream). */
    @Test
    fun ctrlInitEchoedAsAck() {
        val nonce = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val initFrame = Unpooled.buffer(HEADER_SIZE + nonce.size)
        initFrame.writeByte(VERSION.toInt())
        initFrame.writeByte(TYPE_CTRL_INIT.toInt())
        initFrame.writeIntLE(nonce.size)
        initFrame.writeBytes(nonce)

        decoderChannel = EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper())
        decoderChannel.writeInbound(initFrame)

        // Should NOT produce inbound (consumed by stripper)
        assertNull(decoderChannel.readInbound(), "CTRL_INIT must not pass downstream")

        // Should produce outbound ACK
        val ack = decoderChannel.readOutbound<ByteBuf>()
            ?: error("CTRL_INIT should produce an ACK response")

        assertEquals(ack.getByte(0), VERSION, "ACK version")
        assertEquals(ack.getByte(1), TYPE_CTRL_ACK, "ACK frame type")
        assertEquals(ack.getIntLE(2), nonce.size, "ACK payload length")

        // Nonce echoed
        val echoedNonce = ByteArray(nonce.size)
        ack.getBytes(HEADER_SIZE, echoedNonce)
        assertEquals(echoedNonce, nonce, "Nonce echoed in ACK")

        ack.release()
    }

    /** Decoder buffers partial frames until complete. */
    @Test
    fun partialFrameBuffered() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

        encoderChannel = EmbeddedChannel(ClientHeaderPrepender(TYPE_DATA_MATCH))
        encoderChannel.writeOutbound(Unpooled.wrappedBuffer(payload))
        val framed = encoderChannel.readOutbound<ByteBuf>()
            ?: error("Encoder produced no output")

        val allBytes = ByteArray(framed.readableBytes())
        framed.readBytes(allBytes)
        framed.release()

        decoderChannel = EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper())

        // Feed first 4 bytes (incomplete header+payload)
        decoderChannel.writeInbound(Unpooled.wrappedBuffer(allBytes, 0, 4))
        assertNull(decoderChannel.readInbound(), "Incomplete frame should not decode")

        // Feed remaining bytes
        decoderChannel.writeInbound(Unpooled.wrappedBuffer(allBytes, 4, allBytes.size - 4))
        val decoded = decoderChannel.readInbound<ByteBuf>()
            ?: error("Complete frame should decode")

        val result = ByteArray(decoded.readableBytes())
        decoded.readBytes(result)
        decoded.release()
        assertEquals(result, payload)
    }

    /** Large payload (near typical GSM size) roundtrips correctly. */
    @Test
    fun largePayloadRoundtrip() {
        val payload = ByteArray(65_536) { (it % 251).toByte() } // prime mod avoids repeats

        encoderChannel = EmbeddedChannel(ClientHeaderPrepender(TYPE_DATA_MATCH))
        encoderChannel.writeOutbound(Unpooled.wrappedBuffer(payload))
        val framed = encoderChannel.readOutbound<ByteBuf>()
            ?: error("Encoder produced no output")

        decoderChannel = EmbeddedChannel(ClientFrameDecoder(), ClientHeaderStripper())
        decoderChannel.writeInbound(framed)
        val decoded = decoderChannel.readInbound<ByteBuf>()
            ?: error("Decoder produced no output")

        val result = ByteArray(decoded.readableBytes())
        decoded.readBytes(result)
        decoded.release()

        assertEquals(result.size, payload.size)
        assertTrue(result.contentEquals(payload), "64KB payload must survive roundtrip")
    }
}
