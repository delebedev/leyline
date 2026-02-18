package forge.nexus.conformance

import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

/**
 * Parses real client binary captures into [StructuralFingerprint] sequences.
 *
 * Input: directory of S-C_MATCH_*.bin files (MatchServiceToClientMessage protos)
 * Output: ordered list of fingerprints for every GRE message in the recording.
 */
object RecordingParser {

    /**
     * Parse a single MatchServiceToClientMessage payload, extract GRE fingerprints.
     * @param seatFilter if non-null, only include messages addressed to this seat
     */
    fun parsePayload(bytes: ByteArray, seatFilter: Int? = null): List<StructuralFingerprint> {
        val msg = try {
            MatchServiceToClientMessage.parseFrom(bytes)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!msg.hasGreToClientEvent()) return emptyList()
        return msg.greToClientEvent.greToClientMessagesList
            .filter { gre ->
                val seatIds = gre.systemSeatIdsList.map { it.toInt() }
                seatFilter == null || seatIds.isEmpty() || seatFilter in seatIds
            }
            .map { StructuralFingerprint.fromGRE(it) }
    }

    /**
     * Parse all .bin files in a directory, return fingerprints in file-name order.
     * @param seatFilter if non-null, only include messages addressed to this seat
     */
    fun parseDirectory(dir: File, seatFilter: Int? = null): List<StructuralFingerprint> {
        val files = dir.listFiles()
            ?.filter { it.extension == "bin" }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return files.flatMap { parsePayload(it.readBytes(), seatFilter) }
    }
}
