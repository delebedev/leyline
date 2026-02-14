package forge.nexus.protocol

import com.google.protobuf.TextFormat
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dumps outgoing Arena proto messages to `/tmp/arena-dump/` as text-format files.
 *
 * Enabled by `ARENA_DUMP=1` env var (or `-Darena.dump=true`).
 * Each send produces a sequentially numbered file: `001-GameStateMessage.txt`, etc.
 *
 * Usage: inspect files with `cat`, diff against templates, etc.
 */
object ProtoDump {
    private val log = LoggerFactory.getLogger(ProtoDump::class.java)
    private val counter = AtomicInteger(0)
    private val printer = TextFormat.printer()

    val enabled: Boolean = System.getenv("ARENA_DUMP") == "1" ||
        System.getProperty("arena.dump") == "true"

    private val dumpDir: File by lazy {
        File("/tmp/arena-dump").also {
            if (it.exists()) it.listFiles()?.forEach { f -> f.delete() }
            it.mkdirs()
            log.info("Arena proto dump → {}", it.absolutePath)
        }
    }

    /** Dump an outgoing [MatchServiceToClientMessage] to a numbered text file. */
    fun dump(msg: MatchServiceToClientMessage, label: String = "") {
        if (!enabled) return
        val seq = counter.incrementAndGet()
        val tag = label.ifEmpty { classify(msg) }
        val name = "%03d-%s.txt".format(seq, tag)
        val file = File(dumpDir, name)
        file.writeText(printer.printToString(msg))
        log.debug("Dumped {} ({} bytes text)", name, file.length())
    }

    /** Dump raw bytes alongside text for binary comparison. */
    fun dumpBin(msg: MatchServiceToClientMessage, label: String = "") {
        if (!enabled) return
        val seq = counter.get() // use same seq as last text dump
        val tag = label.ifEmpty { classify(msg) }
        val name = "%03d-%s.bin".format(seq, tag)
        File(dumpDir, name).writeBytes(msg.toByteArray())
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
