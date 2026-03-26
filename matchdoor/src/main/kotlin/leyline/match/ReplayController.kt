package leyline.match

/**
 * Control interface for replay playback, exposed to the debug API.
 * ReplayHandler implements this; DebugServer calls it.
 */
interface ReplayController {
    /** Metadata for a single recorded frame. */
    data class FrameInfo(
        val index: Int,
        val fileName: String,
        val greType: String,
        /** "auth", "room", "gre", "other" */
        val category: String,
    )

    /** Current playback position (0-based index into GRE frames). */
    val currentFrame: Int

    /** Total GRE frames available. */
    val totalFrames: Int

    /** Ordered metadata for all GRE frames. */
    val frameIndex: List<FrameInfo>

    /** Advance to next GRE frame. Returns false if already at end. */
    fun next(): Boolean

    /** Current status snapshot for the API. */
    fun status(): ReplayStatus

    data class ReplayStatus(
        val currentFrame: Int,
        val totalFrames: Int,
        val currentFrameInfo: FrameInfo?,
        val atEnd: Boolean,
    )
}
