package leyline.tooling.simclient

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

internal object SimClientLogging {
    private val stdioLock = Any()

    fun configure(verbose: Boolean) {
        if (verbose) return
        withSuppressedStderr {
            val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return@withSuppressedStderr
            context.loggerList.forEach { logger ->
                logger.iteratorForAppenders().asSequence().toList().forEach { appender ->
                    if (appender is ConsoleAppender<*>) logger.detachAppender(appender as Appender<ILoggingEvent>)
                }
            }
        }
    }

    fun <T> withSuppressedStderr(block: () -> T): T =
        synchronized(stdioLock) {
            val original = System.err
            System.setErr(PrintStream(OutputStream.nullOutputStream()))
            try {
                block()
            } finally {
                System.setErr(original)
            }
        }

    fun <T> withRedirectedStdio(
        file: File,
        block: () -> T,
    ): T =
        synchronized(stdioLock) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, false).use { stream ->
                val redirected = PrintStream(stream, true)
                val originalOut = System.out
                val originalErr = System.err
                System.setOut(redirected)
                System.setErr(redirected)
                try {
                    block()
                } finally {
                    System.setOut(originalOut)
                    System.setErr(originalErr)
                }
            }
        }
}
