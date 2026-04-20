package leyline.debug

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Log streaming to the debug panel SSE endpoint.
 *
 * Streams log events to SSE clients via the logback appender.
 * Read-only game state inspection lives in separate analysis tooling.
 */
class DebugCollector(
    private val eventBus: DebugEventBus,
) {
    private val log = LoggerFactory.getLogger(DebugCollector::class.java)

    private val maxLogEntries = 2000
    private val logBuffer = ArrayDeque<LogEntry>(maxLogEntries)
    private var logSeq = 0
    private val sseJson = Json { encodeDefaults = true }

    @Serializable
    data class LogEntry(
        val seq: Int,
        val ts: Long,
        val level: String,
        val logger: String,
        val message: String,
        val thread: String,
    )

    internal fun recordLog(
        ts: Long,
        level: String,
        logger: String,
        message: String,
        thread: String,
    ) {
        val entry: LogEntry
        synchronized(logBuffer) {
            logSeq++
            entry = LogEntry(logSeq, ts, level, logger, message, thread)
            if (logBuffer.size >= maxLogEntries) logBuffer.removeFirst()
            logBuffer.addLast(entry)
        }
        try {
            eventBus.emit("log", sseJson.encodeToString(entry))
        } catch (e: Exception) {
            log.debug("Failed to emit SSE log event", e)
        }
    }

    companion object {
        /**
         * Global instance — set once during server startup.
         *
         * Needed because [DebugLogAppender] is instantiated by logback before
         * DI wiring runs. The appender uses this static reference.
         */
        @Volatile
        var instance: DebugCollector? = null
    }
}

/**
 * Logback appender that feeds log events into [DebugCollector].
 *
 * Uses [DebugCollector.instance] (static) because logback instantiates
 * appenders from XML before our DI wiring runs. Events before wiring
 * are silently dropped.
 */
class DebugLogAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        DebugCollector.instance?.recordLog(
            ts = event.timeStamp,
            level = event.level.toString(),
            logger = event.loggerName.substringAfterLast('.'),
            message = event.formattedMessage.orEmpty(),
            thread = event.threadName.orEmpty(),
        )
    }
}

/**
 * Pub/sub bus for real-time debug events (SSE).
 * Collectors emit typed events; [DebugServer] SSE endpoint subscribes.
 */
class DebugEventBus {
    private val log = LoggerFactory.getLogger(DebugEventBus::class.java)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String, String) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(
        type: String,
        data: String,
    ) {
        for (l in listeners) {
            try {
                l(type, data)
            } catch (e: Exception) {
                log.debug("SSE listener dispatch failed", e)
            }
        }
    }
}
