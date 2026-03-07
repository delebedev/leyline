package leyline.arena

import java.io.File

object Capture {

    fun run(args: List<String>) {
        var out = "/tmp/arena/screen.png"
        var resolution = 1280

        val iter = args.iterator()
        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "--out" -> out = iter.next()
                "--resolution" -> resolution = iter.next().toInt()
                else -> {
                    System.err.println("Unknown flag: $arg")
                    throw SystemExitException(1)
                }
            }
        }

        File(out).parentFile?.mkdirs()

        // capture MTGA window only (crops to window bounds)
        val full = "/tmp/arena/_capture_full.png"
        val bounds = Shell.captureWindow(full)
        if (bounds == null) {
            System.err.println("MTGA window not found")
            throw SystemExitException(1)
        }

        // resize if needed (window capture is at logical resolution ~1920 wide)
        if (resolution < bounds.w) {
            Shell.sips("--resampleWidth", resolution.toString(), full, "--out", out)
            File(full).delete()
        } else {
            File(full).renameTo(File(out))
        }

        val size = File(out).length() / 1024
        println("$out (${size}KB, ${resolution}px)")
    }
}
