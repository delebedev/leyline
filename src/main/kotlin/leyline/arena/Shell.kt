package leyline.arena

import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}

/** MTGA window bounds in logical screen coordinates. */
data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int)

object Shell {

    private val projectDir: String by lazy {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "justfile").exists()) return@lazy dir.absolutePath
            dir = dir.parentFile
        }
        System.getProperty("user.dir")
    }

    /** Cached window bounds with 5s TTL — window doesn't move mid-session. */
    private var cachedBounds: WindowBounds? = null
    private var cachedBoundsMs: Long = 0
    private const val BOUNDS_TTL_MS = 5000L

    /** Last activate timestamp — skip re-activate within 2s window. */
    private var lastActivateMs: Long = 0
    private const val ACTIVATE_DEDUP_MS = 2000L

    fun run(vararg cmd: String, timeoutSec: Long = 30): ShellResult {
        val pb = ProcessBuilder(*cmd)
            .directory(File(projectDir))
            .redirectErrorStream(false)
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        return ShellResult(proc.exitValue(), stdout.trim(), stderr.trim())
    }

    fun ocr(imagePath: String, vararg extraArgs: String): ShellResult =
        run("$projectDir/tools/ocr", imagePath, "--json", *extraArgs)

    /** Activate MTGA window, deduped within 2s. */
    private fun activateMtga() {
        val now = System.currentTimeMillis()
        if (now - lastActivateMs < ACTIVATE_DEDUP_MS) return
        run("osascript", "-e", """tell application "MTGA" to activate""")
        Thread.sleep(150)
        lastActivateMs = System.currentTimeMillis()
    }

    /**
     * Click at screen-absolute coordinates.
     * Activates MTGA window first (Unity ignores background clicks).
     */
    fun click(x: Int, y: Int, action: String = "click"): ShellResult {
        activateMtga()
        return run("$projectDir/tools/click", x.toString(), y.toString(), action)
    }

    fun peekaboo(vararg args: String): ShellResult =
        run("/opt/homebrew/bin/peekaboo", *args)

    fun sips(vararg args: String): ShellResult =
        run("sips", *args)

    /** Get MTGA window bounds via compiled tool (logical points). Cached 5s. */
    fun mtgaWindowBounds(): WindowBounds? {
        val now = System.currentTimeMillis()
        cachedBounds?.let { cached ->
            if (now - cachedBoundsMs < BOUNDS_TTL_MS) return cached
        }

        val r = run("$projectDir/tools/window-bounds")
        if (!r.ok || r.stdout.isBlank()) return null
        val parts = r.stdout.split(" ")
        if (parts.size != 4) return null
        val bounds = WindowBounds(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        cachedBounds = bounds
        cachedBoundsMs = System.currentTimeMillis()
        return bounds
    }

    /**
     * Capture just the MTGA window region.
     * Activates MTGA, captures full screen via peekaboo, crops to window bounds.
     * Returns the window bounds used, or null on failure.
     */
    fun captureWindow(outPath: String): WindowBounds? {
        activateMtga()
        val bounds = mtgaWindowBounds() ?: return null

        val raw = "/tmp/arena/_screen_raw.png"
        // peekaboo captures at logical resolution (1920x1080 on retina)
        val r = peekaboo("image", "--mode", "screen", "--path", raw)
        if (!r.ok) return null

        // crop to window bounds using sips
        sips(
            "--cropOffset", bounds.y.toString(), bounds.x.toString(),
            "--cropToHeightWidth", bounds.h.toString(), bounds.w.toString(),
            raw, "--out", outPath,
        )
        File(raw).delete()
        return bounds
    }
}
