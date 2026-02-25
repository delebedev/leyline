package forge.nexus.debug

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Recording inspector CLI.
 *
 * Commands:
 * - list
 * - summary <session-dir-or-id>
 * - actions <session-dir-or-id> [--card X] [--actor Y] [--limit N]
 * - who-played <session-dir-or-id> --card X
 * - compare <left-session> <right-session>
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    when (val cmd = args[0]) {
        "list" -> {
            val sessions = RecordingInspector.listSessions()
            if (sessions.isEmpty()) {
                println("No recording sessions found.")
                return
            }
            sessions.forEach {
                println("${it.id}\t${it.mode}\t${it.fileCount}\t${it.path}")
            }
        }

        "summary" -> {
            val ref = args.getOrNull(1)
            if (ref.isNullOrBlank()) {
                printUsage()
                return
            }
            val summary = RecordingInspector.summary(ref)
            if (summary == null) {
                println("Session not found or not parseable: $ref")
                return
            }
            println(json.encodeToString(summary))
        }

        "actions" -> {
            val ref = args.getOrNull(1)
            if (ref.isNullOrBlank()) {
                printUsage()
                return
            }
            val card = flagValue(args, "--card")
            val actor = flagValue(args, "--actor")
            val limit = flagValue(args, "--limit")?.toIntOrNull() ?: 500

            val actions = RecordingInspector.actions(ref, cardFilter = card, actorFilter = actor, limit = limit)
            if (actions.isEmpty()) {
                println("No actions found.")
                return
            }
            actions.forEach {
                val actorText = it.actorLabel
                val cardText = it.card ?: it.grpId?.let { id -> "grp:$id" } ?: "?"
                println("#${it.seq}\tT${it.turn ?: 0}\t${it.category}\t$actorText\t$cardText\t${it.file}")
            }
        }

        "who-played" -> {
            val ref = args.getOrNull(1)
            val card = flagValue(args, "--card")
            if (ref.isNullOrBlank() || card.isNullOrBlank()) {
                printUsage()
                return
            }
            val actions = RecordingInspector.actions(ref, cardFilter = card, limit = 5_000)
                .filter { it.category == "PlayLand" || it.category == "CastSpell" }
            if (actions.isEmpty()) {
                println("No matching plays for '$card'.")
                return
            }
            actions.forEach {
                val actorText = it.actorLabel
                val cardText = it.card ?: it.grpId?.let { id -> "grp:$id" } ?: "?"
                println("T${it.turn ?: 0}\t$actorText\t${it.category}\t$cardText\tgs=${it.gsId}\tmsg=${it.msgId}")
            }
        }

        "compare" -> {
            val left = args.getOrNull(1)
            val right = args.getOrNull(2)
            if (left.isNullOrBlank() || right.isNullOrBlank()) {
                printUsage()
                return
            }
            val diff = RecordingInspector.compare(left, right)
            if (diff == null) {
                println("Unable to compare: no parseable actions in one or both sessions.")
                return
            }
            println(json.encodeToString(diff))
        }

        else -> {
            System.err.println("Unknown command: $cmd")
            printUsage()
        }
    }
}

private fun flagValue(args: Array<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    if (idx < 0 || idx + 1 >= args.size) return null
    val value = args[idx + 1]
    if (value.startsWith("--")) return null
    return value
}

private fun printUsage() {
    System.err.println("Usage:")
    System.err.println("  RecordingCli list")
    System.err.println("  RecordingCli summary <session-dir-or-id>")
    System.err.println("  RecordingCli actions <session-dir-or-id> [--card X] [--actor Y] [--limit N]")
    System.err.println("  RecordingCli who-played <session-dir-or-id> --card X")
    System.err.println("  RecordingCli compare <left-session> <right-session>")
}
