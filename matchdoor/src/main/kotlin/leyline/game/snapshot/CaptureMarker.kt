package leyline.game.snapshot

/** Debug metadata attached to every snapshot. Excluded from equality. */
data class CaptureMarker(
    val gsIdBeforeCapture: Int,
    val wallClockMs: Long,
) {
    companion object {
        fun unknown(): CaptureMarker = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 0L)
    }
}
