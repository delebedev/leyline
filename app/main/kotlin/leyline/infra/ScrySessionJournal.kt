package leyline.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Append-only journal for local Leyline match provenance.
 *
 * scry-ts reads this side channel at save time to classify saved games without
 * mutating raw Player.log slices or depending on semantic match IDs.
 */
object ScrySessionJournal {
    private val log = LoggerFactory.getLogger(ScrySessionJournal::class.java)
    private val journalPath: Path = Path.of(System.getProperty("user.home"), ".scry", "leyline-sessions.jsonl")

    fun record(
        matchId: String,
        source: String,
        eventName: String,
        puzzleRef: String? = null,
    ) {
        runCatching {
            Files.createDirectories(journalPath.parent)
            val line = buildJsonObject {
                put("ts", Instant.now().toString())
                put("matchId", matchId)
                put("source", source)
                put("eventName", eventName)
                if (puzzleRef != null) put("puzzleRef", puzzleRef)
            }.toString() + "\n"
            Files.writeString(
                journalPath,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { err ->
            log.warn("Failed to write scry session journal for matchId={}", matchId, err)
        }
    }
}
