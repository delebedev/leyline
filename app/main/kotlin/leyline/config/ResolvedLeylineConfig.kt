package leyline.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The resolved configuration snapshot for one process lifetime, with value
 * provenance and derived resource locations.
 */
class ResolvedLeylineConfig(
    val config: LeylineConfig,
    val provenance: Map<String, Source>,
    val baseDir: File,
    val instance: String?,
    val paths: ResolvedPaths,
) {
    /** Human-readable startup report with value provenance. */
    fun report(head: String): String {
        val tree = json.encodeToJsonElement(LeylineConfig.serializer(), config).jsonObject
        val bySection = SettingsSchema.leaves(LeylineConfig.serializer().descriptor).groupBy { it.path.first() }
        return buildString {
            appendLine("Leyline configuration (head=$head, instance=${instance ?: "default"})")
            appendLine("  base: ${baseDir.absolutePath}")
            for ((section, leaves) in bySection) {
                appendLine("  [$section]")
                for (leaf in leaves.sortedBy { it.path.joinToString(".") }) {
                    val key = leaf.path.joinToString(".")
                    val value = valueAt(tree, leaf.path)
                    val source = provenance[key] ?: Source.DEFAULT
                    appendLine("    $key = ${value?.toString() ?: "null"} [$source]")
                }
            }
            appendLine("  [resolved paths]")
            appendLine("    state: ${paths.stateDir.absolutePath}")
            appendLine("    player_db: ${paths.playerDb.absolutePath}")
            appendLine("    artifacts: ${paths.artifactsRoot.absolutePath}")
            appendLine("    session_journal: ${paths.sessionJournal.absolutePath}")
        }
    }

    private fun valueAt(
        root: JsonObject,
        path: List<String>,
    ): JsonElement? {
        var current: JsonElement? = root
        for (part in path) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
