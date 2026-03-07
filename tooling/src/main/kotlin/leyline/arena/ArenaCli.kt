package leyline.arena

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            """
            Usage: arena <command> [args...]
            Commands: launch, capture, ocr, click, drag, state, errors, wait, board, issues
            """.trimIndent(),
        )
        exitProcess(1)
    }

    val command = args[0]
    val cmdArgs = args.drop(1)

    // issues command reads logs, no need to wrap
    if (command == "issues") {
        SessionLog.printIssues(cmdArgs)
        return
    }

    // Capture stdout/stderr + exit code for session logging
    val origOut = System.out
    val origErr = System.err
    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val teeOut = TeeOutputStream(origOut, capturedOut)
    val teeErr = TeeOutputStream(origErr, capturedErr)
    System.setOut(PrintStream(teeOut))
    System.setErr(PrintStream(teeErr))

    val startMs = System.currentTimeMillis()
    var exitCode = 0
    try {
        when (command) {
            "launch" -> Launch.run(cmdArgs)
            "capture" -> Capture.run(cmdArgs)
            "ocr" -> Ocr.run(cmdArgs)
            "click" -> Click.run(cmdArgs)
            "drag" -> Drag.run(cmdArgs)
            "state" -> State.run(cmdArgs)
            "errors" -> State.errors(cmdArgs)
            "wait" -> Wait.run(cmdArgs)
            "board" -> Board.run(cmdArgs)
            else -> {
                System.err.println("Unknown command: $command")
                exitCode = 1
            }
        }
    } catch (e: SystemExitException) {
        exitCode = e.code
    } catch (_: Exception) {
        exitCode = 1
    } finally {
        System.setOut(origOut)
        System.setErr(origErr)
        val durationMs = System.currentTimeMillis() - startMs
        SessionLog.log(
            command,
            cmdArgs,
            exitCode,
            capturedOut.toString().trim(),
            capturedErr.toString().trim(),
            durationMs,
        )
    }
    if (exitCode != 0) exitProcess(exitCode)
}

/** Tee: writes to both streams simultaneously. */
private class TeeOutputStream(
    private val a: java.io.OutputStream,
    private val b: java.io.OutputStream,
) : java.io.OutputStream() {
    override fun write(byte: Int) {
        a.write(byte)
        b.write(byte)
    }
    override fun write(buf: ByteArray, off: Int, len: Int) {
        a.write(buf, off, len)
        b.write(buf, off, len)
    }
    override fun flush() {
        a.flush()
        b.flush()
    }
}

/** Thrown instead of System.exit() to allow logging before exit. */
class SystemExitException(val code: Int) : Exception()
