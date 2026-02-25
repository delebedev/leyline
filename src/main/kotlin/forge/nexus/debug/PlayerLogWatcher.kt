package forge.nexus.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parsed client-side error from Player.log.
 *
 * Captures exceptions the MTGA client logs when it fails to process
 * our GRE messages (annotation parse failures, missing fields, etc.).
 */
@Serializable
data class ClientError(
    val seq: Int,
    /** ISO-8601 when we captured the error. */
    val ts: String,
    /** e.g. "ArgumentException", "InvalidOperationException" */
    val exceptionType: String,
    /** The exception message text. */
    val message: String,
    /** Full stack trace (if present), newlines preserved. */
    val stackTrace: String = "",
    /** Annotation ID from the JSON payload (if present). */
    val annotationId: Int? = null,
    /** Annotation type number from the JSON payload (if present). */
    val annotationType: List<Int>? = null,
    /** Raw log line(s) for full context. */
    val raw: String,
)

/**
 * Tails the MTGA Player.log file and captures exception lines.
 *
 * Starts from EOF (no historical content). Runs a daemon thread that
 * polls for new content every [pollMs]. Captured errors are:
 * - stored in an in-memory ring buffer (queryable via [snapshot])
 * - written to `client-errors.jsonl` in the session directory
 * - emitted to [DebugEventBus] as `client-error` SSE events
 *
 * macOS only: `~/Library/Logs/Wizards of the Coast/MTGA/Player.log`
 */
class PlayerLogWatcher(
    private val sessionDir: File = NexusPaths.SESSION_DIR,
    private val pollMs: Long = 500,
) {
    private val log = LoggerFactory.getLogger(PlayerLogWatcher::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val json = Json { encodeDefaults = false }
    private val sseJson = Json { encodeDefaults = false }

    // --- In-memory ring buffer ---
    private val maxEntries = 200
    private val buffer = ArrayDeque<ClientError>(maxEntries)
    private val seq = AtomicInteger(0)

    // --- Persistent JSONL writer ---
    private var writer: BufferedWriter? = null

    companion object {
        /** Active watcher instance (set by [start], cleared by [stop]). */
        @Volatile
        var active: PlayerLogWatcher? = null
            private set

        /** macOS Player.log path. */
        val PLAYER_LOG: File = File(
            System.getProperty("user.home"),
            "Library/Logs/Wizards of the Coast/MTGA/Player.log",
        )

        /**
         * Regex for lines like:
         *   `ArgumentException: some message`
         *   `[UnityCrossThreadLogger]KeyNotFoundException: some message`
         */
        private val BARE_EXCEPTION = Regex(
            """(\w+Exception):\s+(.+)""",
        )

        /**
         * Regex for `[UnityCrossThreadLogger]Exception while parsing annotation.`
         * followed by optional JSON on the same line.
         */
        private val ANNOTATION_EXCEPTION = Regex(
            """\[UnityCrossThreadLogger\]Exception while parsing annotation\.\s*(\{.+)?$""",
        )

        /**
         * Regex for `[TaskLogger]Deserialization function failed for <Type>`
         * followed by JSON text + exception details on continuation lines.
         */
        private val DESERIALIZATION_FAILED = Regex(
            """\[TaskLogger\]Deserialization function failed for\s+(\S+).*Exception:\s*(.+)""",
        )

        /**
         * Fallback: captures the type even when Exception detail is on the next line.
         * e.g. `[TaskLogger]Deserialization function failed for Wizards.Unification.Models.StoreStatusV2`
         */
        private val DESERIALIZATION_FAILED_SHORT = Regex(
            """\[TaskLogger\]Deserialization function failed for\s+(\S+)""",
        )

        /** Extract ExceptionType from JSON payload. */
        private val JSON_EXCEPTION_TYPE = Regex(
            """"ExceptionType"\s*:\s*"([^"]+)"""",
        )

        /** Extract exception Message from JSON payload. */
        private val JSON_MESSAGE = Regex(
            """"Message"\s*:\s*"([^"]+)"""",
        )

        /** Extract StackTraceString from JSON payload. */
        private val JSON_STACK = Regex(
            """"StackTraceString"\s*:\s*"([^"]+)"""",
        )

        /** Extract annotation Id from JSON payload. */
        private val JSON_ANNOTATION_ID = Regex(
            """"Id"\s*:\s*(\d+)""",
        )

        /** Extract annotation Type array from JSON payload. */
        private val JSON_ANNOTATION_TYPE = Regex(
            """"Type"\s*:\s*\[([^\]]*)]""",
        )

        /** Exception types to suppress — too noisy, not actionable. */
        private val SUPPRESSED_TYPES = setOf(
            "ArgumentNullException",
            "ArgumentOutOfRangeException",
            "NullReferenceException",
        )
    }

    fun start() {
        if (!PLAYER_LOG.exists()) {
            log.info("Player.log not found at {}; client error watcher disabled", PLAYER_LOG)
            return
        }
        if (!running.compareAndSet(false, true)) return
        active = this

        // Open JSONL writer
        writer = try {
            sessionDir.mkdirs()
            BufferedWriter(FileWriter(File(sessionDir, "client-errors.jsonl"), true))
        } catch (e: Exception) {
            log.warn("Cannot open client-errors.jsonl: {}", e.message)
            null
        }

        thread = Thread({
            try {
                tailLoop()
            } catch (e: InterruptedException) {
                // normal shutdown
            } catch (e: Exception) {
                log.error("PlayerLogWatcher crashed", e)
            }
        }, "player-log-watcher").apply {
            isDaemon = true
            start()
        }
        log.info("Player.log watcher started (tailing from EOF)")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        active = null
        thread?.interrupt()
        thread?.join(2000)
        thread = null
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null

        val count = seq.get()
        if (count > 0) {
            log.info(
                "Player.log watcher stopped — {} client error(s) captured → {}",
                count,
                File(sessionDir, "client-errors.jsonl").absolutePath,
            )
        } else {
            log.info("Player.log watcher stopped — no client errors")
        }
    }

    /** Return errors with seq > [since]. */
    fun snapshot(since: Int = 0): List<ClientError> = synchronized(buffer) {
        buffer.filter { it.seq > since }
    }

    /** Total error count this session. */
    fun count(): Int = seq.get()

    // --- Tail loop ---

    private fun tailLoop() {
        val raf = RandomAccessFile(PLAYER_LOG, "r")
        // Seek to end — ignore pre-existing content
        raf.seek(raf.length())

        val lineBuf = StringBuilder()
        while (running.get()) {
            val line = raf.readLine()
            if (line == null) {
                // Check if file was truncated (client relaunch)
                val currentLen = PLAYER_LOG.length()
                if (currentLen < raf.filePointer) {
                    log.debug("Player.log truncated (client relaunch?) — resetting to start")
                    raf.seek(0)
                }
                Thread.sleep(pollMs)
                continue
            }
            processLine(line)
        }
        raf.close()
    }

    private fun processLine(line: String) {
        // Fast pre-filter: skip lines that can't contain exceptions
        if ("Exception" !in line) return

        // Try annotation exception (richest data)
        val annotMatch = ANNOTATION_EXCEPTION.find(line)
        if (annotMatch != null) {
            val jsonPayload = annotMatch.groupValues[1]
            if (jsonPayload.isNotBlank()) {
                val exType = JSON_EXCEPTION_TYPE.find(jsonPayload)?.groupValues?.get(1)
                if (exType != null && exType in SUPPRESSED_TYPES) return
                recordAnnotationError(jsonPayload, line)
            } else {
                // Bare "Exception while parsing annotation." without JSON
                record(
                    exceptionType = "AnnotationParseException",
                    message = "Exception while parsing annotation (no details)",
                    raw = line,
                )
            }
            return
        }

        // Try deserialization failure (TaskLogger)
        val deserMatch = DESERIALIZATION_FAILED.find(line)
        if (deserMatch != null) {
            record(
                exceptionType = "DeserializationException",
                message = "Failed to deserialize ${deserMatch.groupValues[1]}: ${deserMatch.groupValues[2]}",
                raw = line,
            )
            return
        }
        val deserShort = DESERIALIZATION_FAILED_SHORT.find(line)
        if (deserShort != null) {
            record(
                exceptionType = "DeserializationException",
                message = "Failed to deserialize ${deserShort.groupValues[1]}",
                raw = line,
            )
            return
        }

        // Try bare exception line
        val bareMatch = BARE_EXCEPTION.find(line)
        if (bareMatch != null) {
            val exType = bareMatch.groupValues[1]
            if (exType in SUPPRESSED_TYPES) return
            record(
                exceptionType = exType,
                message = bareMatch.groupValues[2],
                raw = line,
            )
            return
        }
    }

    private fun recordAnnotationError(jsonPayload: String, rawLine: String) {
        val exType = JSON_EXCEPTION_TYPE.find(jsonPayload)?.groupValues?.get(1) ?: "UnknownException"
        val msg = JSON_MESSAGE.find(jsonPayload)?.groupValues?.get(1) ?: "Unknown error"
        val stack = JSON_STACK.find(jsonPayload)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?: ""
        val annotId = JSON_ANNOTATION_ID.find(jsonPayload)?.groupValues?.get(1)?.toIntOrNull()
        val annotType = JSON_ANNOTATION_TYPE.find(jsonPayload)?.groupValues?.get(1)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }

        record(
            exceptionType = exType,
            message = msg,
            stackTrace = stack,
            annotationId = annotId,
            annotationType = annotType,
            raw = rawLine,
        )
    }

    private fun record(
        exceptionType: String,
        message: String,
        stackTrace: String = "",
        annotationId: Int? = null,
        annotationType: List<Int>? = null,
        raw: String,
    ) {
        val entry = ClientError(
            seq = seq.incrementAndGet(),
            ts = Instant.now().toString(),
            exceptionType = exceptionType,
            message = message,
            stackTrace = stackTrace,
            annotationId = annotationId,
            annotationType = annotationType,
            raw = raw,
        )

        // Ring buffer
        synchronized(buffer) {
            if (buffer.size >= maxEntries) buffer.removeFirst()
            buffer.addLast(entry)
        }

        // Persistent JSONL
        try {
            writer?.let { w ->
                w.write(json.encodeToString(entry))
                w.newLine()
                w.flush()
            }
        } catch (e: Exception) {
            log.warn("Failed to write client-errors.jsonl: {}", e.message)
        }

        // SSE
        try {
            DebugEventBus.emit("client-error", sseJson.encodeToString(entry))
        } catch (_: Exception) {}

        // Console — distinctive prefix for visibility
        log.warn("CLIENT ERROR [{}]: {}", exceptionType, message)
    }
}
