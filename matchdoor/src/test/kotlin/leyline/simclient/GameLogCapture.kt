package leyline.simclient

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Records WARN+ERROR log events during a single simclient game so the batch
 * test can attribute leyline warnings / exceptions back to the (deck × seed)
 * that produced them.
 *
 * Logback's root logger is global. Simclient runs serially
 * (`maxParallelForks = 1` is enforced by the simclient task), so attaching the
 * appender for the duration of one game is safe.
 *
 * **Caller contract:** before calling [stopAndDrain], drain any in-flight
 * engine-thread work (the simclient driver's `runOneGame` already concedes
 * and drains on exit). Logback's `AppenderBase.doAppend` serialises append
 * operations, but the underlying `ListAppender.list` is a plain `ArrayList`
 * — concurrent appends during `stopAndDrain`'s `toList()` would risk a
 * `ConcurrentModificationException`. The serial-runs invariant + drain-on-
 * exit make this safe in practice.
 *
 * Usage from `runOne`:
 *   val tap = GameLogCapture().apply { start() }
 *   ...
 *   val driver = SimClientDriver(...)
 *   val stats = driver.runOneGame()      // already drains the bridge
 *   val (warns, errors) = tap.stopAndDrain()
 */
class GameLogCapture {
    private val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
    private val rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME)
    private val appender =
        ListAppender<ILoggingEvent>().apply {
            context = ctx
            name = "simclient-game-tap"
            start()
        }

    fun start() {
        appender.list.clear()
        rootLogger.addAppender(appender)
    }

    /**
     * Detach the appender and return:
     *   first  — WARN counts grouped by logger name
     *   second — exception class counts from any throwable carried on log events,
     *            plus ERROR-level events without throwables grouped by logger
     */
    fun stopAndDrain(): Pair<Map<String, Int>, Map<String, Int>> {
        rootLogger.detachAppender(appender)
        val events = appender.list.toList()
        appender.list.clear()

        val warns =
            events
                .filter { it.level == Level.WARN }
                .groupingBy { it.loggerName }
                .eachCount()

        val errors =
            events
                .flatMap { ev ->
                    val tp = ev.throwableProxy
                    when {
                        tp != null -> generateSequence(tp) { it.cause }.map { it.className }.toList()
                        ev.level == Level.ERROR -> listOf(ev.loggerName)
                        else -> emptyList()
                    }
                }.groupingBy { it }
                .eachCount()

        return warns to errors
    }
}
