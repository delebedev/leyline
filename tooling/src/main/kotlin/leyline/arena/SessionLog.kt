package leyline.arena

import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Append-only JSONL session log for arena CLI.
 *
 * Every command invocation is logged with args, result, duration, and error flag.
 * Log directory: /tmp/arena/sessions/<date>/
 * One file per session (process lifetime), named by start timestamp.
 */
object SessionLog {

    private val sessionDir: String by lazy {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        "/tmp/arena/sessions/$date"
    }

    private val logFile: File by lazy {
        val ts = DateTimeFormatter.ofPattern("HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        File(sessionDir, "$ts.jsonl").also { it.parentFile?.mkdirs() }
    }

    private val startMs = System.currentTimeMillis()

    /** Log a command invocation. Called from ArenaCli wrapper. */
    fun log(command: String, args: List<String>, exitCode: Int, stdout: String, stderr: String, durationMs: Long) {
        val error = exitCode != 0
        val elapsed = System.currentTimeMillis() - startMs
        val entry = buildString {
            append("{")
            append("\"t\":$elapsed,")
            append("\"ts\":\"${Instant.now()}\",")
            append("\"cmd\":\"$command\",")
            append("\"args\":${jsonArray(args)},")
            append("\"exit\":$exitCode,")
            append("\"ms\":$durationMs")
            if (error) {
                append(",\"error\":true")
                if (stderr.isNotBlank()) {
                    append(",\"stderr\":${jsonString(stderr.take(500))}")
                }
            }
            if (stdout.isNotBlank() && stdout.length < 200) {
                append(",\"out\":${jsonString(stdout)}")
            }
            append("}")
        }
        try {
            FileWriter(logFile, true).use { it.appendLine(entry) }
        } catch (_: Exception) {
            // logging should never break the command
        }
    }

    /** Print issues summary from recent session logs. */
    fun printIssues(args: List<String>) {
        val days = if (args.isNotEmpty()) args[0].toIntOrNull() ?: 1 else 1
        val sessionsDir = File("/tmp/arena/sessions")
        if (!sessionsDir.exists()) {
            println("No session logs found")
            return
        }

        data class Issue(val cmd: String, val args: String, val stderr: String, var count: Int = 1)

        val issues = mutableMapOf<String, Issue>()
        var totalCommands = 0
        var totalErrors = 0
        var totalSessions = 0

        val cutoff = System.currentTimeMillis() - days * 86400_000L
        sessionsDir.listFiles()?.sortedDescending()?.forEach { dateDir ->
            if (!dateDir.isDirectory) return@forEach
            dateDir.listFiles()?.sortedDescending()?.forEach { logFile ->
                if (!logFile.name.endsWith(".jsonl")) return@forEach
                if (logFile.lastModified() < cutoff) return@forEach
                totalSessions++
                logFile.readLines().forEach { line ->
                    totalCommands++
                    if (line.contains("\"error\":true")) {
                        totalErrors++
                        // extract cmd, args, stderr for grouping
                        val cmd = extractJsonField(line, "cmd") ?: "?"
                        val cmdArgs = extractJsonField(line, "args") ?: "[]"
                        val stderr = extractJsonField(line, "stderr") ?: ""
                        val key = "$cmd|$cmdArgs"
                        issues.compute(key) { _, existing ->
                            existing?.also { it.count++ }
                                ?: Issue(cmd, cmdArgs, stderr)
                        }
                    }
                }
            }
        }

        println("=== Arena Session Summary (last ${days}d) ===")
        println("Sessions: $totalSessions | Commands: $totalCommands | Errors: $totalErrors")
        if (issues.isEmpty()) {
            println("No issues found.")
            return
        }
        println()
        println("Issues (by frequency):")
        issues.values.sortedByDescending { it.count }.forEach { issue ->
            println("  ${issue.count}x  ${issue.cmd} ${issue.args}")
            if (issue.stderr.isNotBlank()) {
                println("       → ${issue.stderr.take(120)}")
            }
        }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val prefix = "\"$field\":"
        val idx = json.indexOf(prefix)
        if (idx < 0) return null
        val start = idx + prefix.length
        return when {
            json[start] == '"' -> {
                val end = json.indexOf('"', start + 1)
                if (end < 0) null else json.substring(start + 1, end)
            }
            json[start] == '[' -> {
                val end = json.indexOf(']', start)
                if (end < 0) null else json.substring(start, end + 1)
            }
            else -> {
                val end = json.indexOfAny(charArrayOf(',', '}'), start)
                if (end < 0) null else json.substring(start, end)
            }
        }
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun jsonArray(items: List<String>): String =
        items.joinToString(",", "[", "]") { jsonString(it) }
}
