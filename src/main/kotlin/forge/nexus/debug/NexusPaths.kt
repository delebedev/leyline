package forge.nexus.debug

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Default filesystem paths for debug/recording artifacts. */
object NexusPaths {
    private val sessionTag: String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    val RECORDINGS = File("/tmp/arena-recordings")
    val ENGINE_DUMP = File("/tmp/arena-recordings/$sessionTag/engine")
    val CAPTURE_ROOT = File("/tmp/arena-recordings/$sessionTag/capture")
    val CAPTURE_PAYLOADS = File(CAPTURE_ROOT, "payloads")
    val CAPTURE_FRAMES = File(CAPTURE_ROOT, "frames")

    /** Symlink latest session for quick access. */
    val LATEST: File = File("/tmp/arena-recordings/latest").also { link ->
        val target = File("/tmp/arena-recordings/$sessionTag")
        target.mkdirs()
        link.delete()
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {}
    }
}
