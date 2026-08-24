package leyline.config

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlElement
import net.peanuuutz.tomlkt.TomlTable
import java.io.File

/** Configuration resolution failure — the process must not start. */
class ConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Effective-value source, reported at startup for provenance. */
enum class Source { DEFAULT, TOML, ENV }

/**
 * Resolves the application configuration snapshot for one process lifetime.
 *
 * Inputs are supplied explicitly ([env], [baseDir], [defaultStateDir]) so
 * resolution is deterministic and testable. Effective value precedence is
 * typed default < TOML < `LEYLINE_*` environment override; the environment
 * name is derived mechanically from the canonical key path (for example
 * `native.fd_port` → `LEYLINE_NATIVE_FD_PORT`).
 *
 * Unknown TOML keys, malformed overrides, and invalid combinations fail
 * startup with [ConfigException]. Relative path values resolve against
 * [baseDir], never the process working directory.
 */
class LeylineConfigResolver(
    private val baseDir: File,
    private val env: Map<String, String>,
    /** Durable user-level state directory used when `paths.state` is unset. */
    private val defaultStateDir: File = File(System.getProperty("user.home"), "Library/Application Support/dev.leyline"),
) {
    private val toml = Toml { ignoreUnknownKeys = false }
    private val json = Json { encodeDefaults = true }

    fun resolve(configFile: File = File(baseDir, LeylineConfig.FILENAME)): ResolvedLeylineConfig {
        val text = configFile.takeIf { it.isFile }?.readText().orEmpty()
        val (base, table) = decodeToml(text, configFile)
        rejectTomlSecrets(table)
        val baseJson = json.encodeToJsonElement(LeylineConfig.serializer(), base).jsonObject
        val overrides = collectEnvOverrides()
        val effectiveJson = overrides.fold(baseJson) { tree, (path, value) -> setAt(tree, path, value) }
        val effective =
            try {
                json.decodeFromJsonElement(LeylineConfig.serializer(), effectiveJson)
            } catch (e: Exception) {
                throw ConfigException("Invalid effective configuration: ${e.message}", e)
            }
        validate(effective)
        val instance = env["LEYLINE_INSTANCE"]?.takeIf { it.isNotBlank() }
        val provenance = buildProvenance(table, overrides)
        val paths = ResolvedPaths.resolve(baseDir, effective.paths, instance, defaultStateDir)
        return ResolvedLeylineConfig(
            config = effective,
            provenance = provenance,
            baseDir = baseDir,
            instance = instance,
            paths = paths,
        )
    }

    private fun decodeToml(
        text: String,
        configFile: File,
    ): Pair<LeylineConfig, TomlTable> =
        try {
            val table = toml.parseToTomlTable(text)
            toml.decodeFromTomlElement(LeylineConfig.serializer(), table) to table
        } catch (e: Exception) {
            throw ConfigException("Failed to parse ${configFile.absolutePath}: ${e.message}", e)
        }

    private fun rejectTomlSecrets(table: TomlTable) {
        val secret = WebSettings.SECRET_PATHS.firstOrNull { valueAt(table, it.split('.')) != null } ?: return
        throw ConfigException("$secret must be supplied through ${SettingsSchema.envNameOf(secret.split('.'))}")
    }

    private fun validate(config: LeylineConfig) {
        try {
            config.validate()
            instanceName()?.let { name -> require(isValidInstanceName(name)) { "LEYLINE_INSTANCE must be a simple name, got '$name'" } }
        } catch (e: IllegalArgumentException) {
            throw ConfigException(e.message ?: "Invalid configuration", e)
        }
    }

    private fun instanceName(): String? = env["LEYLINE_INSTANCE"]?.takeIf { it.isNotBlank() }

    private fun isValidInstanceName(name: String): Boolean =
        name != "." && name != ".." && Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""").matches(name)

    private fun collectEnvOverrides(): List<Pair<List<String>, JsonElement>> =
        SettingsSchema.leaves(LeylineConfig.serializer().descriptor).mapNotNull { leaf ->
            val envName = SettingsSchema.envNameOf(leaf.path)
            val raw = env[envName]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value =
                try {
                    parseScalar(leaf.kind, raw)
                } catch (e: IllegalArgumentException) {
                    throw ConfigException("Invalid value for $envName: '$raw' (${e.message})", e)
                }
            leaf.path to value
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen") // only scalar kinds can appear in the settings graph; the else rejects structural kinds.
    private fun parseScalar(
        kind: SerialKind,
        raw: String,
    ): JsonElement =
        when (kind) {
            PrimitiveKind.INT -> JsonPrimitive(raw.toInt())
            PrimitiveKind.LONG -> JsonPrimitive(raw.toLong())
            PrimitiveKind.DOUBLE -> JsonPrimitive(raw.toDouble())
            PrimitiveKind.BOOLEAN -> JsonPrimitive(raw.toBooleanStrict())
            PrimitiveKind.STRING, SerialKind.ENUM -> JsonPrimitive(raw)
            else -> throw ConfigException("Environment override not supported for kind $kind")
        }

    private fun setAt(
        root: JsonObject,
        path: List<String>,
        value: JsonElement,
    ): JsonObject {
        val head = path.first()
        val updated =
            if (path.size == 1) {
                value
            } else {
                val child = root[head] as? JsonObject ?: JsonObject(emptyMap())
                setAt(child, path.drop(1), value)
            }
        return JsonObject(root + (head to updated))
    }

    private fun buildProvenance(
        table: TomlTable,
        overrides: List<Pair<List<String>, JsonElement>>,
    ): Map<String, Source> {
        val envPaths = overrides.map { it.first.joinToString(".") }.toSet()
        return SettingsSchema.leaves(LeylineConfig.serializer().descriptor).associate { leaf ->
            val key = leaf.path.joinToString(".")
            val source =
                when {
                    key in envPaths -> Source.ENV
                    valueAt(table, leaf.path) != null -> Source.TOML
                    else -> Source.DEFAULT
                }
            key to source
        }
    }

    private fun valueAt(
        root: TomlTable,
        path: List<String>,
    ): TomlElement? {
        var current: TomlElement? = root
        for (part in path) {
            current = (current as? TomlTable)?.get(part) ?: return null
        }
        return current
    }
}
