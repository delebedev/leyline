package leyline.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.protocol.FdEnvelope
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ring buffer of Front Door messages for debug API.
 *
 * Analogous to [DebugCollector] for Match Door — records every
 * C→S / S→C FD message with decoded CmdType, transactionId, JSON payload.
 *
 * Queryable at `GET /api/fd-messages?since=N`.
 */
class FdDebugCollector(
    private val eventBus: DebugEventBus,
) {

    private val maxEntries = 200
    private val entries = ArrayDeque<FdEntry>(maxEntries)
    private val seqGen = AtomicInteger(0)
    private val lock = Any()

    @Serializable
    data class FdEntry(
        val seq: Int,
        val ts: Long,
        val dir: String, // "C2S" or "S2C"
        val cmdType: Int?,
        val cmdTypeName: String?,
        val transactionId: String?,
        val jsonPayload: String?,
        val envelopeType: String,
    )

    fun record(direction: String, decoded: FdEnvelope.FdMessage) {
        val entry = FdEntry(
            seq = seqGen.incrementAndGet(),
            ts = System.currentTimeMillis(),
            dir = direction,
            cmdType = decoded.cmdType,
            cmdTypeName = decoded.cmdType?.let { FdEnvelope.cmdTypeName(it) },
            transactionId = decoded.transactionId,
            jsonPayload = decoded.jsonPayload,
            envelopeType = decoded.envelopeType.name,
        )
        synchronized(lock) {
            if (entries.size >= maxEntries) entries.removeFirst()
            entries.addLast(entry)
        }
        // Emit SSE event for real-time debug panel
        try {
            val json = Json.encodeToString(entry)
            eventBus.emit("fd-message", json)
        } catch (_: Exception) {}
    }

    fun snapshot(since: Int = 0): List<FdEntry> = synchronized(lock) {
        entries.filter { it.seq > since }.toList()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        seqGen.set(0)
    }
}
