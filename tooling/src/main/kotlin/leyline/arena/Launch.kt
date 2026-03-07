package leyline.arena

object Launch {

    private const val DEFAULT_WIDTH = 1920
    private const val DEFAULT_HEIGHT = 1080

    fun run(args: List<String>) {
        var width = DEFAULT_WIDTH
        var height = DEFAULT_HEIGHT
        var kill = false

        val iter = args.iterator()
        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "--width" -> width = iter.next().toInt()
                "--height" -> height = iter.next().toInt()
                "--kill" -> kill = true
                else -> {
                    System.err.println("Unknown arg: $arg")
                    throw SystemExitException(1)
                }
            }
        }

        if (kill) {
            Shell.run("osascript", "-e", """tell application "MTGA" to quit""")
            Thread.sleep(2000)
        }

        val r = Shell.run(
            "open", "-a", "MTGA", "--args",
            "-screen-width", width.toString(),
            "-screen-height", height.toString(),
            "-screen-fullscreen", "0",
        )
        if (!r.ok) {
            System.err.println("Failed to launch MTGA: ${r.stderr}")
            throw SystemExitException(1)
        }
        // Invalidate cached window bounds since resolution changed
        println("MTGA launched (${width}x$height windowed)")
    }
}
