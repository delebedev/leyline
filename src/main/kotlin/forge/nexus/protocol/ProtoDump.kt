package forge.nexus.protocol

import com.google.protobuf.TextFormat
import forge.nexus.NexusPaths
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dumps outgoing client proto messages as text-format + binary files.
 *
 * Always enabled — every game is recorded. Files go to the session's
 * `engine/` subdirectory under [NexusPaths.ENGINE_DUMP].
 *
 * Each send produces a sequentially numbered file: `001-GameStateMessage.txt`, etc.
 *
 * Usage: inspect files with `cat`, diff against templates, etc.
 */
object ProtoDump {
    private val log = LoggerFactory.getLogger(ProtoDump::class.java)
    private val counter = AtomicInteger(0)
    private val printer = TextFormat.printer()

    private val dumpDir: File by lazy {
        NexusPaths.ENGINE_DUMP.also {
            it.mkdirs()
            log.info("Client proto dump → {}", it.absolutePath)
        }
    }

    /** Dump an outgoing [MatchServiceToClientMessage] to numbered text + binary files. */
    fun dump(msg: MatchServiceToClientMessage, label: String = "") {
        val seq = counter.incrementAndGet()
        val tag = label.ifEmpty { classify(msg) }
        val txtName = "%03d-%s.txt".format(seq, tag)
        val txtFile = File(dumpDir, txtName)
        txtFile.writeText(printer.printToString(msg))
        // Also write binary for proto-compare CLI (RecordingParser reads .bin)
        File(dumpDir, "%03d-%s.bin".format(seq, tag)).writeBytes(msg.toByteArray())
        log.debug("Dumped {} ({} bytes text)", txtName, txtFile.length())
    }

    private fun classify(msg: MatchServiceToClientMessage): String = when {
        msg.hasAuthenticateResponse() -> "AuthResp"
        msg.hasMatchGameRoomStateChangedEvent() -> "RoomState"
        msg.hasGreToClientEvent() -> {
            val types = msg.greToClientEvent.greToClientMessagesList
                .map { it.type.name.removeSuffix("_695e").removeSuffix("_aa0d") }
            types.joinToString("+")
        }
        else -> "Unknown"
    }
}
