package forge.nexus.debug

import java.io.File

/** Default filesystem paths for debug/recording artifacts. */
object NexusPaths {
    val RECORDINGS = File("/tmp/arena-recordings")
    val ENGINE_DUMP = File("/tmp/arena-dump")
    val CAPTURE_ROOT = File("/tmp/arena-capture")
    val CAPTURE_PAYLOADS = File(CAPTURE_ROOT, "payloads")
    val CAPTURE_FRAMES = File(CAPTURE_ROOT, "frames")
}
