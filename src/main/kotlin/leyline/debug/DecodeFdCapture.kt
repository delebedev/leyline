package leyline.debug

import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
import leyline.recording.FdFrameRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter

/**
 * Re-decode FD raw frame .bin files → fd-frames.jsonl.
 *
 * Usage: `just decode-golden [dir]`
 *
 * Reads `frames/` directory, decodes each FD DATA frame via [FdEnvelope],
 * writes fresh `fd-frames.jsonl` next to the frames directory.
 * Handles compressed payloads (4-byte size prefix + gzip).
 */
fun main(args: Array<String>) {
    val dir = if (args.isNotEmpty()) {
        File(args[0])
    } else {
        // Auto-detect: latest capture directory
        File("recordings").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
            ?.let { File(it, "capture") }
            ?: error("No recordings found. Run: just serve-proxy")
    }

    val framesDir = if (dir.name == "frames") dir else File(dir, "frames")
    require(framesDir.isDirectory) { "Frames directory not found: $framesDir" }

    val outFile = File(framesDir.parentFile, "fd-frames.jsonl")
    val jsonFmt = Json { encodeDefaults = true }

    val frames = framesDir.listFiles()
        ?.filter { it.name.endsWith(".bin") && "FD" in it.name && "DATA" in it.name }
        ?.sortedBy { it.name.substringBefore('_').toLongOrNull() ?: 0 }
        ?: emptyList()

    println("Decoding ${frames.size} FD frames from ${framesDir.absolutePath}")

    var decoded = 0
    var withPayload = 0
    PrintWriter(outFile).use { writer ->
        for (file in frames) {
            val raw = file.readBytes()
            if (raw.size < ClientFrameDecoder.HEADER_SIZE) continue

            val payload = raw.copyOfRange(ClientFrameDecoder.HEADER_SIZE, raw.size)
            if (payload.isEmpty()) continue

            val seq = file.name.substringBefore('_').toLongOrNull() ?: continue
            val direction = when {
                "C-S" in file.name || "C_S" in file.name -> "C2S"
                "S-C" in file.name || "S_C" in file.name -> "S2C"
                else -> "?"
            }

            try {
                val msg = FdEnvelope.decode(payload)
                val record = FdFrameRecord(
                    seq = seq,
                    dir = direction,
                    cmdType = msg.cmdType,
                    cmdTypeName = msg.cmdType?.let { FdEnvelope.cmdTypeName(it) },
                    transactionId = msg.transactionId,
                    envelopeType = msg.envelopeType.name,
                    jsonPayload = msg.jsonPayload,
                )
                writer.println(jsonFmt.encodeToString(record))
                decoded++
                if (msg.jsonPayload != null && msg.jsonPayload.length > 100) withPayload++
            } catch (e: Exception) {
                System.err.println("  WARN: ${file.name}: ${e.message}")
            }
        }
    }

    println("Wrote $outFile ($decoded frames, $withPayload with payload)")
}
