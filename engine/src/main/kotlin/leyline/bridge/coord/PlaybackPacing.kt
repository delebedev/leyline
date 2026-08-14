package leyline.bridge.coord

internal fun pacePlayback(
    delayMs: Int,
    multiplier: Double,
) {
    val adjusted = (delayMs * multiplier).toLong()
    if (adjusted <= 0) return
    try {
        Thread.sleep(adjusted)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
