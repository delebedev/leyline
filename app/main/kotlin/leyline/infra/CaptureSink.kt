package leyline.infra

import io.netty.buffer.ByteBuf
import kotlinx.serialization.json.Json
import leyline.LeylinePaths
import leyline.debug.FdDebugCollector
import leyline.frontdoor.wire.CmdType
import leyline.frontdoor.wire.FdEnvelope
import leyline.protocol.ClientFrameDecoder
import leyline.recording.FdFrameRecord
import leyline.recording.RecordingDecoder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Reassembles proxy TCP chunks into full protocol frames and stores.
 * Lives in server/ (not debug/) because it's called directly from
 * the proxy handler pipeline in [LeylineServer].
 *
 * Stores:
 * - Lossless frames: `<capture>/frames/<seq>_<dir>_<type>.bin`
 * - Data payloads:   `<capture>/payloads/<seq>_<dir>_<type>.bin`
 * - FD decoded JSONL: `<capture>/fd-frames.jsonl` (CmdType + JSON per frame)
 */
class CaptureSink(
    private val fdCollector: FdDebugCollector,
    /** Seat label for MD frame subdirectories (e.g. "seat-1"). Null = flat layout (backward compat). */
    private val mdLabel: String? = null,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(CaptureSink::class.java)
    private val lock = Any()
    private val seq = AtomicLong(0)
    private val pending = mutableMapOf<String, ByteArray>()

    /** Capture accepts larger payloads than the stub decoder — real Arena sends 1.7MB+ StartHook. */
    private companion object {
        const val CAPTURE_MAX_PAYLOAD = 8_388_608 // 8 MB
    }

    private val payloadDir: File get() = LeylinePaths.CAPTURE_PAYLOADS
    private val frameDir: File get() = LeylinePaths.CAPTURE_FRAMES
    private val fdJsonlFile: File get() = File(LeylinePaths.CAPTURE_ROOT, "fd-frames.jsonl")

    /** MD frames route to seat-labeled subdirs when [mdLabel] is set, else fall back to flat layout. */
    private val mdFrameDir: File
        get() = if (mdLabel != null) {
            File(LeylinePaths.CAPTURE_ROOT, "$mdLabel/md-frames")
        } else {
            frameDir
        }

    private val mdPayloadDir: File
        get() = if (mdLabel != null) {
            File(LeylinePaths.CAPTURE_ROOT, "$mdLabel/md-payloads")
        } else {
            payloadDir
        }
    private var fdJsonlWriter: PrintWriter? = null

    /** Flush current session data and reset state for a new session. */
    fun rotate() {
        synchronized(lock) {
            flushMdFrames()
            fdJsonlWriter?.close()
            fdJsonlWriter = null
            seq.set(0)
            pending.clear()
        }
    }

    private val jsonFmt = Json { encodeDefaults = true }

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

                if (payloadLen < 0 || payloadLen > CAPTURE_MAX_PAYLOAD) {
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

        val isMd = dir.startsWith("MD")
        val targetFrameDir = if (isMd) mdFrameDir else frameDir
        val targetPayloadDir = if (isMd) mdPayloadDir else payloadDir

        targetFrameDir.mkdirs()
        File(targetFrameDir, "$base.bin").writeBytes(frame)

        // Only write data payloads for parser/trace tools.
        if (ft == ClientFrameDecoder.TYPE_CTRL_INIT || ft == ClientFrameDecoder.TYPE_CTRL_ACK) return
        if (payloadLen <= 0) return

        val payload = frame.copyOfRange(ClientFrameDecoder.HEADER_SIZE, ClientFrameDecoder.HEADER_SIZE + payloadLen)
        targetPayloadDir.mkdirs()
        File(targetPayloadDir, "$base.bin").writeBytes(payload)

        // Decode FD envelope and write to JSONL + debug collector
        if (dir.startsWith("FD")) {
            val direction = if ("C→S" in dir || "C-S" in dir) "C2S" else "S2C"
            try {
                val decoded = FdEnvelope.decode(payload)
                fdCollector.record(direction, decoded)

                val record = FdFrameRecord(
                    seq = fileSeq,
                    dir = direction,
                    cmdType = decoded.cmdType,
                    cmdTypeName = decoded.cmdType?.let { CmdType.nameOf(it) },
                    transactionId = decoded.transactionId,
                    envelopeType = decoded.envelopeType.name,
                    jsonPayload = decoded.jsonPayload,
                )
                writeFdJsonl(record)
            } catch (e: Exception) {
                log.warn("FD decode failed seq={} dir={}: {}", fileSeq, direction, e.message)
                val record = FdFrameRecord(
                    seq = fileSeq,
                    dir = direction,
                    cmdType = null,
                    cmdTypeName = null,
                    transactionId = null,
                    envelopeType = "DECODE_FAILED",
                    jsonPayload = null,
                )
                writeFdJsonl(record)
            }
        }
    }

    private fun writeFdJsonl(record: FdFrameRecord) {
        if (fdJsonlWriter == null) {
            fdJsonlFile.parentFile.mkdirs()
            fdJsonlWriter = PrintWriter(FileWriter(fdJsonlFile, true), true)
        }
        fdJsonlWriter?.println(jsonFmt.encodeToString(record))
    }

    override fun close() {
        synchronized(lock) {
            fdJsonlWriter?.close()
            fdJsonlWriter = null
        }
        flushMdFrames()
    }

    /** Decode MD payloads → md-frames.jsonl. Safe to call multiple times (idempotent overwrite). */
    fun flushMdFrames() {
        try {
            val sessionDir = LeylinePaths.SESSION_DIR
            if (!sessionDir.isDirectory) return

            // Collect MD payloads from both flat layout and seat subdirectories
            val messages = mutableListOf<RecordingDecoder.DecodedMessage>()

            // Flat layout (backward compat) — decodeDirectory auto-discovers capture/payloads/
            messages.addAll(RecordingDecoder.decodeDirectory(sessionDir))

            // Seat subdirectories (e.g. capture/seat-1/md-payloads/)
            val captureRoot = LeylinePaths.CAPTURE_ROOT
            if (captureRoot.isDirectory) {
                for (seatDir in captureRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("seat-") } ?: emptyList()) {
                    val mdPayloads = File(seatDir, "md-payloads")
                    if (mdPayloads.isDirectory) {
                        messages.addAll(RecordingDecoder.decodeDirectory(mdPayloads))
                    }
                }
            }

            if (messages.isEmpty()) return
            val sorted = messages.sortedBy { it.index }
            val outFile = File(sessionDir, "md-frames.jsonl")
            outFile.printWriter().use { pw ->
                for (msg in sorted) pw.println(RecordingDecoder.toJsonLine(msg))
            }
            log.info("Wrote {} MD messages to {}", sorted.size, outFile)
        } catch (e: Exception) {
            log.warn("MD auto-decode failed: {}", e.message)
        }
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
