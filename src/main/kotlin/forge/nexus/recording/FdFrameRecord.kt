package forge.nexus.recording

import kotlinx.serialization.Serializable

/**
 * Serializable summary of a single Front Door data frame.
 *
 * Written by [forge.nexus.server.CaptureSink] during proxy capture,
 * read by [forge.nexus.debug.DecodeFdCapture] for offline re-decode.
 */
@Serializable
data class FdFrameRecord(
    val seq: Long,
    val dir: String,
    val cmdType: Int? = null,
    val cmdTypeName: String? = null,
    val transactionId: String? = null,
    val envelopeType: String? = null,
    val jsonPayload: String? = null,
)
