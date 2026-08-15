package leyline.bridge.handoff

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Versioned target facts retained until projection consumption. */
internal class PendingTargetStore {
    private val nextVersion = AtomicLong()
    private val entries = ConcurrentLinkedQueue<InteractivePromptBridge.PendingTargetEntry>()

    fun add(spec: InteractivePromptBridge.PendingTarget) {
        entries.add(
            InteractivePromptBridge.PendingTargetEntry(
                version = nextVersion.incrementAndGet(),
                spec = spec.copy(affectees = spec.affectees.map { it.copy() }),
            ),
        )
    }

    fun specs(): List<InteractivePromptBridge.PendingTarget> = entries.map { it.spec }

    fun entries(): List<InteractivePromptBridge.PendingTargetEntry> = entries.toList()

    fun consume(specs: List<InteractivePromptBridge.PendingTarget>) {
        entries.removeIf { queued -> specs.any { queued.spec === it } }
    }

    fun consumeEntries(entriesToConsume: List<InteractivePromptBridge.PendingTargetEntry>) {
        val versions = entriesToConsume.mapTo(mutableSetOf()) { it.version }
        if (versions.isNotEmpty()) entries.removeIf { it.version in versions }
    }

    fun clear() {
        entries.clear()
    }
}
