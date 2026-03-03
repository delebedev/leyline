package leyline.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object Click {

    fun run(args: List<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: arena click <target> [--double] [--right] [--retry N] [--exact]")
            throw SystemExitException(1)
        }

        val target = args[0]
        val action = when {
            "--double" in args -> "double"
            "--right" in args -> "right"
            else -> "click"
        }
        val exact = "--exact" in args
        val retryIdx = args.indexOf("--retry")
        val maxRetries = if (retryIdx >= 0 && retryIdx + 1 < args.size) {
            args[retryIdx + 1].toIntOrNull() ?: 0
        } else {
            0
        }

        // x,y coords — treated as window-relative, offset by window origin
        val coordMatch = Regex("""^(\d+),(\d+)$""").matchEntire(target)
        if (coordMatch != null) {
            val wx = coordMatch.groupValues[1].toInt()
            val wy = coordMatch.groupValues[2].toInt()
            val bounds = Shell.mtgaWindowBounds()
            if (bounds == null) {
                System.err.println("MTGA window not found")
                throw SystemExitException(1)
            }
            val sx = bounds.x + wx
            val sy = bounds.y + wy
            val r = Shell.click(sx, sy, action)
            if (!r.ok) {
                System.err.println("click failed: ${r.stderr}")
                throw SystemExitException(1)
            }
            println("clicked ($wx, $wy) → screen ($sx, $sy)")
            return
        }

        // text target — capture window, OCR, click (with optional retry)
        val img = "/tmp/arena/_click_capture.png"
        File(img).parentFile?.mkdirs()

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                System.err.println("retry $attempt/$maxRetries for \"$target\"...")
                Thread.sleep(500)
            }

            val bounds = Shell.captureWindow(img)
            if (bounds == null) {
                if (attempt == maxRetries) {
                    System.err.println("MTGA window not found")
                    throw SystemExitException(1)
                }
                continue
            }

            val ocrArgs = mutableListOf("--find", target)
            if (exact) ocrArgs += "--exact"
            val ocr = Shell.ocr(img, *ocrArgs.toTypedArray())
            if (!ocr.ok || ocr.stdout.isBlank()) {
                if (attempt == maxRetries) {
                    File(img).delete()
                    System.err.println("\"$target\" not found on screen")
                    throw SystemExitException(1)
                }
                continue
            }

            val matches = Json.parseToJsonElement(ocr.stdout).jsonArray
            if (matches.isEmpty()) {
                if (attempt == maxRetries) {
                    File(img).delete()
                    System.err.println("\"$target\" not found on screen")
                    throw SystemExitException(1)
                }
                continue
            }

            File(img).delete()
            val first = matches[0].jsonObject
            // OCR coords are window-relative (image is cropped to window)
            val cx = first["cx"]!!.jsonPrimitive.content.toDouble().toInt()
            val cy = first["cy"]!!.jsonPrimitive.content.toDouble().toInt()
            // offset to screen coords for CGEvent
            val sx = bounds.x + cx
            val sy = bounds.y + cy

            val r = Shell.click(sx, sy, action)
            if (!r.ok) {
                System.err.println("click failed: ${r.stderr}")
                throw SystemExitException(1)
            }
            println("clicked \"$target\" at ($cx, $cy) → screen ($sx, $sy)")
            return
        }
    }
}
