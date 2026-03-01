package forge.nexus.bridge

import forge.game.Game

/**
 * Builds structured diagnostic messages when a bridge timeout occurs.
 *
 * Captures engine thread stack trace, game phase, priority holder, and stack
 * contents — everything needed to diagnose why the engine thread didn't respond.
 *
 * Used by both [GameActionBridge] and [InteractivePromptBridge] on timeout.
 * Only fires on timeout path — zero overhead on happy path.
 */
object BridgeTimeoutDiagnostic {

    /**
     * Build a structured multi-line diagnostic message.
     *
     * @param bridgeName human-readable bridge identifier ("GameActionBridge" or "InteractivePromptBridge")
     * @param timeoutMs the timeout that expired
     * @param game current game instance (nullable — may not be set yet)
     * @param engineThread the daemon thread running the engine loop (nullable)
     * @param lastContext bridge-specific context string (pending action state, prompt request, etc.)
     */
    fun buildMessage(
        bridgeName: String,
        timeoutMs: Long,
        game: Game?,
        engineThread: Thread?,
        lastContext: String,
    ): String = buildString {
        appendLine("Bridge timeout after ${timeoutMs}ms ($bridgeName)")

        // Game state snapshot
        if (game != null) {
            try {
                val handler = game.phaseHandler
                val phase = handler?.phase?.name ?: "unknown"
                val active = handler?.playerTurn?.name ?: "unknown"
                val priority = handler?.priorityPlayer?.name ?: "unknown"
                appendLine("Phase: $phase | Active: $active | Priority: $priority")

                val stack = game.stack
                if (stack != null && !stack.isEmpty) {
                    val items = (0 until stack.size()).mapNotNull { i ->
                        try {
                            stack.peekAbility()?.hostCard?.name
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val desc = if (items.isNotEmpty()) items.joinToString(", ") else "unreadable"
                    appendLine("Stack: ${stack.size()} item(s) ($desc)")
                } else {
                    appendLine("Stack: empty")
                }
            } catch (e: Exception) {
                appendLine("Game state: unreadable (${e.message})")
            }
        } else {
            appendLine("Game: not available")
        }

        // Last bridge context
        appendLine("Last posted: $lastContext")

        // Engine thread stack trace
        if (engineThread != null) {
            try {
                val frames = engineThread.stackTrace
                if (frames.isNotEmpty()) {
                    appendLine("Engine thread (${engineThread.name}):")
                    // Show up to 20 frames, prioritize forge.* frames
                    val relevant = frames.take(20)
                    for (frame in relevant) {
                        appendLine("  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
                    }
                    if (frames.size > 20) {
                        appendLine("  ... ${frames.size - 20} more frames")
                    }
                } else {
                    appendLine("Engine thread: no stack frames (thread may have exited)")
                }
            } catch (e: Exception) {
                appendLine("Engine thread: couldn't capture trace (${e.message})")
            }
        } else {
            appendLine("Engine thread: not available")
        }
    }.trimEnd()
}
