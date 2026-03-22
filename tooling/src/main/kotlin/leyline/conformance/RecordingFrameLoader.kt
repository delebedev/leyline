package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.File

/**
 * Loads raw GRE proto messages from recording .bin files — lossless.
 *
 * Unlike [RecordingParser] (which returns [StructuralFingerprint]),
 * this returns the full [GREToClientMessage] with all fields preserved.
 */
object RecordingFrameLoader {

    private val RECORDINGS_DIR = File("recordings")

    data class IndexedGREMessage(
        val frameIndex: Int,
        val message: GREToClientMessage,
    )

    /** Load all S-C GRE messages from a session, in frame order. */
    fun load(session: String, seat: Int = 1): List<IndexedGREMessage> {
        val payloadsDir = resolvePayloadsDir(session, seat) ?: return emptyList()
        return loadFromDir(payloadsDir, seat)
    }

    /** Load and filter to a specific [GREMessageType]. */
    fun loadByType(session: String, type: GREMessageType, seat: Int = 1): List<IndexedGREMessage> =
        load(session, seat).filter { it.message.type == type }

    /** Load and filter by gsId. */
    fun loadByGsId(session: String, gsId: Int, seat: Int = 1): List<IndexedGREMessage> =
        load(session, seat).filter { it.message.gameStateId == gsId }

    /** Load from an arbitrary directory of .bin files. */
    fun loadFromDir(dir: File, seatFilter: Int? = null): List<IndexedGREMessage> {
        val binFiles =
            dir.listFiles()
                ?.filter { it.name.contains("S-C") && it.extension == "bin" }
                ?.sortedBy { it.name }
                ?: return emptyList()

        return binFiles.flatMap { file ->
            val frameIndex = file.name.take(9).trimStart('0').ifEmpty { "0" }.toInt()
            RecordingParser.parseToGRE(file.readBytes(), seatFilter)
                .map { IndexedGREMessage(frameIndex, it) }
        }
    }

    private fun resolvePayloadsDir(session: String, seat: Int): File? {
        // Seat-specific layout (current)
        val seatDir = RECORDINGS_DIR.resolve("$session/capture/seat-$seat/md-payloads")
        if (seatDir.isDirectory) return seatDir
        // Flat layout (older captures)
        val flatDir = RECORDINGS_DIR.resolve("$session/capture/md-payloads")
        if (flatDir.isDirectory) return flatDir
        return null
    }
}
