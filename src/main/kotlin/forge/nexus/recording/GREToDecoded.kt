package forge.nexus.recording

import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Converts live [GREToClientMessage] protobuf objects into
 * [RecordingDecoder.DecodedMessage] so engine output and recording
 * captures share a single format for semantic comparison.
 */
object GREToDecoded {

    private const val LIVE_SOURCE = "live"

    /**
     * Convert a live GRE message into a [RecordingDecoder.DecodedMessage].
     *
     * @param gre the protobuf message from the engine
     * @param index monotonic sequence number within the session
     * @param source optional label for the message origin (default "live")
     */
    fun convert(
        gre: GREToClientMessage,
        index: Int,
        source: String = LIVE_SOURCE,
    ): RecordingDecoder.DecodedMessage = RecordingDecoder.decodeGRE(gre, index, source)
}
