package leyline.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.File

object Wait {

    fun run(args: List<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: arena wait <condition> [--timeout 30]")
            System.err.println("Conditions: phase=MAIN1, turn=3, text=\"Play\", no-text=\"Loading\"")
            throw SystemExitException(1)
        }

        val condition = args[0]
        var timeoutSec = 30

        val iter = args.drop(1).iterator()
        while (iter.hasNext()) {
            when (iter.next()) {
                "--timeout" -> timeoutSec = iter.next().toInt()
            }
        }

        val timeoutMs = timeoutSec * 1000L
        val start = System.currentTimeMillis()

        val matched = when {
            condition.startsWith("text=") -> pollOcr(condition.removePrefix("text="), present = true, timeoutMs)
            condition.startsWith("no-text=") -> pollOcr(condition.removePrefix("no-text="), present = false, timeoutMs)
            condition.contains("=") -> State.pollState(condition, timeoutMs)
            else -> {
                System.err.println("Unknown condition format: $condition")
                throw SystemExitException(1)
            }
        }

        val elapsed = (System.currentTimeMillis() - start) / 1000.0

        if (matched) {
            println("matched $condition (${String.format("%.1f", elapsed)}s)")
        } else {
            System.err.println("timeout waiting for $condition (${timeoutSec}s)")
            throw SystemExitException(1)
        }
    }

    private fun pollOcr(text: String, present: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val img = "/tmp/arena/_wait_capture.png"
        File(img).parentFile?.mkdirs()
        var lastSize = -1L

        while (System.currentTimeMillis() < deadline) {
            Shell.captureWindow(img) ?: run {
                Thread.sleep(500)
                continue
            }

            // skip OCR if capture unchanged (same file size = same screen)
            val size = File(img).length()
            if (size == lastSize) {
                Thread.sleep(500)
                continue
            }
            lastSize = size

            val r = Shell.ocr(img, "--find", text)

            val found = r.ok &&
                r.stdout.isNotBlank() &&
                Json.parseToJsonElement(r.stdout).jsonArray.isNotEmpty()

            if (found == present) {
                File(img).delete()
                return true
            }
            Thread.sleep(500)
        }
        File(img).delete()
        return false
    }
}
