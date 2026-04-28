package leyline.game.data

import org.yaml.snakeyaml.Yaml
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Per-card YAML fixture parser and index. Reads each YAML file under the
 * test resources directory `test-cards` into a [Fixture] (client identity
 * plus optional rules data) and exposes name- and grpId-keyed lookups.
 *
 * Two shapes:
 * - **Slim** ([Fixture.rules] is null): Forge cardsfolder owns rules data
 *   (P/T, types, colors, mana cost). Used for cards in Forge cardsfolder.
 * - **Full** ([Fixture.rules] is non-null): self-contained. Used for tokens
 *   and Alchemy / digital-only cards Forge has no entry for.
 *
 * Field IDs (colors, types, subtypes, supertypes, ability category) are
 * client enum integers as stored in `Cards.*` columns.
 *
 * Closure invariant: every grpId referenced in `tokens` or `linkedFaces`
 * has its own YAML in the same directory. Closure walking lives in
 * [leyline.conformance.FixtureCardLoader], not here.
 */
object TestCardFixtures {
    private const val DEFAULT_RESOURCE_DIR = "test-cards"

    /**
     * Client identity for a card — the integers Forge cardsfolder doesn't
     * carry. Stamped onto a Forge-derived [CardData] for slim fixtures.
     */
    data class Identity(
        val name: String,
        val grpId: Int,
        val titleId: Int,
        val expansionCode: String,
        val abilities: List<Ability>,
        val tokens: Map<Int, Int>,
        val linkedFaces: List<Int>,
        val isToken: Boolean,
        val isPrimaryCard: Boolean,
    ) {
        data class Ability(
            val id: Int,
            val textId: Int,
            val category: Int,
            val baseId: Int,
            val activationMana: List<Pair<ManaColor, Int>>,
            val modalChildren: List<Int>,
        )
    }

    /** Forge-redundant rules data; present on Full fixtures, null on Slim. */
    data class Rules(
        val power: String,
        val toughness: String,
        val colors: List<Int>,
        val types: List<Int>,
        val subtypes: List<Int>,
        val supertypes: List<Int>,
        val manaCost: List<Pair<ManaColor, Int>>,
    )

    /** A loaded fixture. Slim ⇔ [rules] is null; Full ⇔ [rules] is non-null. */
    data class Fixture(
        val identity: Identity,
        val rules: Rules?,
    ) {
        val isFull: Boolean get() = rules != null
    }

    private val byName: Map<String, Fixture> by lazy {
        val all = loadAllFixtures()
        val map = mutableMapOf<String, Fixture>()
        for (f in all) {
            map[f.identity.name] = f
            // Forge names tokens "Soldier Token"; client DB stores them as "Soldier".
            // Index both forms so callers passing either resolve.
            if (f.identity.isToken) map["${f.identity.name} Token"] = f
        }
        map
    }
    private val byGrpId: Map<Int, Fixture> by lazy { loadAllFixtures().associateBy { it.identity.grpId } }

    /** Lookup fixture by display name (or "<Name> Token" alias for token fixtures). */
    fun findFixture(name: String): Fixture? = byName[name]

    /** Lookup fixture by grpId. */
    fun findFixtureByGrpId(grpId: Int): Fixture? = byGrpId[grpId]

    /** All loaded fixtures, indexed by display name. */
    val all: Map<String, Fixture> get() = byName

    // --- Loading ---

    private fun loadAllFixtures(): List<Fixture> {
        val dir = resolveResourceDir()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "yaml" }
                .map { parseFile(it) }
                .toList()
        }
    }

    private fun resolveResourceDir(): Path {
        val url = Thread.currentThread().contextClassLoader
            .getResource(DEFAULT_RESOURCE_DIR)
            ?: error("Resource directory '$DEFAULT_RESOURCE_DIR' not on classpath")
        return Paths.get(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFile(path: Path): Fixture {
        return try {
            val raw = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
                ?: error("file is empty")

            val identity = Identity(
                name = raw.requireField<String>("name"),
                grpId = raw.requireField<Number>("grpId").toInt(),
                titleId = raw.requireField<Number>("titleId").toInt(),
                expansionCode = raw["expansionCode"] as String? ?: "",
                abilities = (raw["abilities"] as List<*>? ?: emptyList<Any>()).mapIndexed { i, entry ->
                    val m = entry as Map<String, Any?>
                    Identity.Ability(
                        id = (m["id"] as Number?)?.toInt() ?: error("abilities[$i].id missing"),
                        textId = (m["textId"] as Number?)?.toInt() ?: error("abilities[$i].textId missing"),
                        category = (m["category"] as Number?)?.toInt() ?: error("abilities[$i].category missing"),
                        baseId = (m["baseId"] as Number?)?.toInt() ?: 0,
                        activationMana = parseManaCost(m["mana"] as String? ?: ""),
                        modalChildren = (m["modalChildren"] as List<*>? ?: emptyList<Any>())
                            .map { (it as Number).toInt() },
                    )
                },
                tokens = (raw["tokens"] as Map<*, *>? ?: emptyMap<Any, Any>())
                    .entries.associate { (k, v) ->
                        val key = when (k) {
                            is Number -> k.toInt()
                            is String -> k.toInt()
                            else -> error("token key '$k' is neither Number nor String")
                        }
                        key to (v as Number).toInt()
                    },
                linkedFaces = (raw["linkedFaces"] as List<*>? ?: emptyList<Any>())
                    .map { (it as Number).toInt() },
                isToken = raw["isToken"] as Boolean? ?: false,
                isPrimaryCard = raw["isPrimaryCard"] as Boolean? ?: true,
            )

            // Full shape includes Forge-derivable rules fields. Slim omits them.
            val hasRulesFields = listOf("power", "toughness", "colors", "types", "subtypes", "supertypes", "manaCost")
                .any { raw.containsKey(it) }
            val rules = if (hasRulesFields) {
                Rules(
                    power = raw["power"] as String? ?: "",
                    toughness = raw["toughness"] as String? ?: "",
                    colors = (raw["colors"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    types = (raw["types"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    subtypes = (raw["subtypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    supertypes = (raw["supertypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    manaCost = parseManaCost(raw["manaCost"] as String? ?: ""),
                )
            } else {
                null
            }
            Fixture(identity, rules)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse fixture $path: ${e.message}", e)
        }
    }

    private inline fun <reified T> Map<String, Any?>.requireField(name: String): T {
        val v = this[name] ?: error("required field '$name' missing")
        return v as? T ?: error("field '$name' has wrong type: expected ${T::class.simpleName}, got ${v::class.simpleName}")
    }
}
