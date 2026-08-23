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
    /**
     * Human-readable startup report: active head, instance, absolute paths,
     * endpoints, value provenance, and redacted secrets.
     *
     * [redactedPaths] lists canonical keys whose values must never be echoed
     * (secrets). The web slice populates them from web-auth secrets.
     */
    fun report(
        head: String,
        redactedPaths: Set<String> = emptySet(),
    ): String {
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
                    val display = if (key in redactedPaths) "<redacted>" else value?.toString() ?: "null"
                    appendLine("    $key = $display [$source]")
                }
            }
            appendLine("  [resolved paths]")
            appendLine("    state: ${paths.stateDir.absolutePath}")
            appendLine("    player_db: ${paths.playerDb.absolutePath}")
            appendLine("    artifacts: ${paths.artifactsRoot.absolutePath}")
            appendLine("    engine_dump: ${paths.engineDump.absolutePath}")
            appendLine("    sessions: ${paths.sessionsRoot.absolutePath}")
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
