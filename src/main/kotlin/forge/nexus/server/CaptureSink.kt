package forge.nexus.server

import forge.nexus.debug.NexusPaths
import forge.nexus.protocol.ClientFrameDecoder
import io.netty.buffer.ByteBuf
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Reassembles proxy TCP chunks into full protocol frames and stores.
 * Lives in server/ (not debug/) because it's called directly from
 * the proxy handler pipeline in [NexusServer].
 *
 * Stores:
 * - Lossless frames: `<capture>/frames/<seq>_<dir>_<type>.bin`
 * - Data payloads:   `<capture>/payloads/<seq>_<dir>_<type>.bin`
 */
internal object CaptureSink {
    private val lock = Any()
    private val seq = AtomicLong(0)
    private val pending = mutableMapOf<String, ByteArray>()

    private val payloadDir = NexusPaths.CAPTURE_PAYLOADS
    private val frameDir = NexusPaths.CAPTURE_FRAMES

    fun ingestChunk(dir: String, buf: ByteBuf) {
        val bytes = ByteArray(buf.readableBytes())
        buf.getBytes(buf.readerIndex(), bytes)

        synchronized(lock) {
            payloadDir.mkdirs()
            frameDir.mkdirs()

            val prev = pending[dir] ?: ByteArray(0)
            val merged = prev + bytes

            var offset = 0
            while (merged.size - offset >= ClientFrameDecoder.HEADER_SIZE) {
                val payloadLen = readIntLE(merged, offset + ClientFrameDecoder.LENGTH_OFFSET)

                if (payloadLen < 0 || payloadLen > ClientFrameDecoder.MAX_PAYLOAD) {
                    // Desync guard: shift by one byte until a plausible header appears.
                    offset += 1
                    continue
                }

                val frameLen = ClientFrameDecoder.HEADER_SIZE + payloadLen
                if (merged.size - offset < frameLen) break

                val frame = merged.copyOfRange(offset, offset + frameLen)
                writeFrame(dir, frame)
                offset += frameLen
            }

            pending[dir] = if (offset < merged.size) merged.copyOfRange(offset, merged.size) else ByteArray(0)
        }
    }

    private fun writeFrame(dir: String, frame: ByteArray) {
        if (frame.size < ClientFrameDecoder.HEADER_SIZE) return

        val ft = frame[1]
        val payloadLen = readIntLE(frame, ClientFrameDecoder.LENGTH_OFFSET)
        if (payloadLen < 0 || frame.size < ClientFrameDecoder.HEADER_SIZE + payloadLen) return

        val fileSeq = seq.incrementAndGet()
        val base = "%09d_%s_%s".format(fileSeq, sanitize(dir), frameTypeName(ft))

        File(frameDir, "$base.bin").writeBytes(frame)

        // Only write data payloads for parser/trace tools.
        if (ft == ClientFrameDecoder.TYPE_CTRL_INIT || ft == ClientFrameDecoder.TYPE_CTRL_ACK) return
        if (payloadLen <= 0) return

        val payload = frame.copyOfRange(ClientFrameDecoder.HEADER_SIZE, ClientFrameDecoder.HEADER_SIZE + payloadLen)
        File(payloadDir, "$base.bin").writeBytes(payload)
    }

    private fun sanitize(value: String): String =
        value.replace("→", "-").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}

internal fun frameTypeName(ft: Byte) = when (ft) {
    ClientFrameDecoder.TYPE_CTRL_INIT -> "CTRL_INIT"
    ClientFrameDecoder.TYPE_CTRL_ACK -> "CTRL_ACK"
    ClientFrameDecoder.TYPE_DATA_FD -> "DATA"
    ClientFrameDecoder.TYPE_DATA_MATCH -> "MATCH_DATA"
    else -> "0x${String.format("%02x", ft)}"
}
