package leyline.arena

import java.io.File

object Ocr {

    fun run(args: List<String>) {
        var findText: String? = null

        val iter = args.iterator()
        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "--find" -> findText = iter.next()
                "--json" -> {} // default, ignored
                "--no-json" -> {} // not supported yet
                else -> {
                    System.err.println("Unknown flag: $arg")
                    throw SystemExitException(1)
                }
            }
        }

        // capture MTGA window only — OCR coords are window-relative
        val img = "/tmp/arena/_ocr_capture.png"
        File(img).parentFile?.mkdirs()

        val bounds = Shell.captureWindow(img)
        if (bounds == null) {
            System.err.println("MTGA window not found")
            throw SystemExitException(1)
        }

        val ocrArgs = mutableListOf<String>()
        if (findText != null) {
            ocrArgs += "--find"
            ocrArgs += findText
        }

        val r = Shell.ocr(img, *ocrArgs.toTypedArray())
        File(img).delete()

        if (!r.ok) {
            if (findText != null) {
                System.err.println("\"$findText\" not found")
            } else {
                System.err.println("OCR failed: ${r.stderr}")
            }
            throw SystemExitException(1)
        }

        println(r.stdout)
    }
}
