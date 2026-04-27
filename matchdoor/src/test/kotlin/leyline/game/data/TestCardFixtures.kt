package leyline.game.data

import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import org.yaml.snakeyaml.Yaml
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Per-card YAML fixture loader. Reads each YAML file under the test resources
 * directory `test-cards` and exposes them as either Arena-identity-only ([Slim])
 * for cards Forge owns rules data for, or self-contained ([Full]) for tokens
 * and Alchemy/digital-only cards Forge has no entry for.
 *
 * Schema mirrors the narrow column subset that [ExposedCardRepository] reads.
 * Field IDs (colors, types, subtypes, supertypes, ability category) are
 * Arena's enum integers as stored in `Cards.*` columns.
 *
 * Fixtures form a closed graph: every grpId referenced in `tokens` or
 * `linkedFaces` must have its own YAML in the same directory.
 */
object TestCardFixtures {
    private const val DEFAULT_RESOURCE_DIR = "test-cards"

    /**
     * Arena identity for a card — the integers Forge cardsfolder doesn't carry.
     * Stamped onto a Forge-derived [CardData] by [ArenaIdentityOverlay] for
     * slim fixtures.
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

    /**
     * A loaded fixture — either Slim (rules data lives in Forge) or Full
     * (self-contained, used for tokens / Alchemy where Forge has no entry).
     */
    sealed interface Fixture {
        val identity: Identity

        data class Slim(override val identity: Identity) : Fixture

        data class Full(
            override val identity: Identity,
            val rules: Rules,
        ) : Fixture {
            data class Rules(
                val power: String,
                val toughness: String,
                val colors: List<Int>,
                val types: List<Int>,
                val subtypes: List<Int>,
                val supertypes: List<Int>,
                val manaCost: List<Pair<ManaColor, Int>>,
            )
        }
    }

    private val byName: Map<String, Fixture> by lazy {
        val all = loadAllFixtures()
        val map = mutableMapOf<String, Fixture>()
        for (f in all) {
            map[f.identity.name] = f
            // Forge names tokens "Soldier Token"; Arena DB stores them as "Soldier".
            // Index both forms so callers passing either resolve.
            if (f.identity.isToken) map["${f.identity.name} Token"] = f
        }
        map
    }
    private val byGrpId: Map<Int, Fixture> by lazy { loadAllFixtures().associateBy { it.identity.grpId } }

    /** Lookup fixture by display name. */
    fun findFixture(name: String): Fixture? = byName[name]

    /** Lookup fixture by grpId. Used to walk the fixture graph (linkedFaces / tokens). */
    fun findFixtureByGrpId(grpId: Int): Fixture? = byGrpId[grpId]

    /** Convenience: returns full closure (the named card + its linked faces and produced tokens). */
    fun findClosure(name: String): List<Fixture> {
        val root = findFixture(name) ?: return emptyList()
        val seen = mutableSetOf<Int>()
        val ordered = mutableListOf<Fixture>()
        val stack = ArrayDeque<Int>()
        stack.add(root.identity.grpId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val f = byGrpId[id] ?: continue
            ordered += f
            stack.addAll(f.identity.linkedFaces)
            stack.addAll(f.identity.tokens.values)
        }
        return ordered
    }

    /**
     * Register a card and its closure into [repo] using **only** Full fixtures.
     * Slim fixtures need Forge-derived rules data — use the FixtureCardLoader
     * (in conformance package) which threads Forge through.
     *
     * Errors loudly if the named card is missing or any closure entry is Slim.
     */
    fun registerFull(repo: InMemoryCardRepository, cardName: String) {
        val closure = findClosure(cardName)
        check(closure.isNotEmpty()) {
            "No fixture found for card '$cardName' under $DEFAULT_RESOURCE_DIR/. " +
                "Generate via `card-fixtures emit \"$cardName\"`."
        }
        for (f in closure) {
            check(f is Fixture.Full) {
                "Closure for '$cardName' contains slim fixture '${f.identity.name}' — " +
                    "use FixtureCardLoader (Forge-aware) to register slim cards."
            }
            applyFull(repo, f)
        }
    }

    /**
     * Register every Full fixture (tokens, Alchemy, etc.). Slim fixtures are
     * skipped — they need Forge-derived rules data.
     */
    fun registerAllFull(repo: InMemoryCardRepository) {
        for (f in byName.values) {
            if (f is Fixture.Full) applyFull(repo, f)
        }
    }

    /**
     * Register a single Full fixture into [repo] without walking its closure.
     * Used by the joining layer (FixtureCardLoader) which dispatches Full
     * fixtures here while handling Slim fixtures via Forge.
     */
    fun applyFull(repo: InMemoryCardRepository, fixture: Fixture.Full) = doApplyFull(repo, fixture)

    private fun doApplyFull(repo: InMemoryCardRepository, fixture: Fixture.Full) {
        val id = fixture.identity
        val abilityIds = id.abilities.map { it.id to it.textId }
        val abilityKinds = id.abilities.map { ab ->
            if (ab.category == 1) SlotKind.Activated else SlotKind.Intrinsic
        }
        val data = CardData(
            grpId = id.grpId,
            titleId = id.titleId,
            power = fixture.rules.power,
            toughness = fixture.rules.toughness,
            colors = fixture.rules.colors,
            types = fixture.rules.types,
            subtypes = fixture.rules.subtypes,
            supertypes = fixture.rules.supertypes,
            abilityIds = abilityIds,
            abilityKinds = abilityKinds,
            manaCost = fixture.rules.manaCost,
            tokenGrpIds = id.tokens,
            linkedFaceGrpIds = id.linkedFaces,
        )
        repo.registerData(data, id.name)
        registerAbilityMetadata(repo, id)
    }

    /**
     * Register the [AbilityInfo], [ModalAbilityInfo], and per-card keyword
     * map entries from the fixture's ability list. Used by both [applyFull]
     * and the slim path (after Forge-derived CardData has been constructed
     * and registered).
     */
    fun registerAbilityMetadata(repo: InMemoryCardRepository, identity: Identity) {
        val baseIdToKeyword = KEYWORD_BASE_IDS.entries.associate { (k, v) -> v to k }
        val keywordMap = mutableMapOf<String, Int>()
        for (ab in identity.abilities) {
            if (ab.baseId != 0 || ab.activationMana.isNotEmpty()) {
                repo.registerAbilityInfo(ab.id, AbilityInfo(ab.baseId, ab.activationMana))
            }
            if (ab.modalChildren.isNotEmpty()) {
                repo.registerModalOptions(identity.grpId, ModalAbilityInfo(ab.id, ab.modalChildren))
            }
            // Also populate the test-only keyword map (used by
            // InMemoryCardRepository.findTestKeywordAbilityGrpId) for parity
            // with the AbilityIdDeriver-driven path. Keys are uppercase keyword
            // names (WARP, SNEAK, FLASHBACK, ...).
            baseIdToKeyword[ab.baseId]?.let { kw -> keywordMap[kw] = ab.id }
        }
        if (keywordMap.isNotEmpty()) {
            repo.registerKeywordAbilityGrpIds(identity.grpId, keywordMap)
        }
    }

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
        val raw = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
            ?: error("Empty fixture: $path")

        val identity = Identity(
            name = raw["name"] as String,
            grpId = (raw["grpId"] as Number).toInt(),
            titleId = (raw["titleId"] as Number).toInt(),
            expansionCode = raw["expansionCode"] as String? ?: "",
            abilities = (raw["abilities"] as List<*>? ?: emptyList<Any>()).map { entry ->
                val m = entry as Map<String, Any?>
                Identity.Ability(
                    id = (m["id"] as Number).toInt(),
                    textId = (m["textId"] as Number).toInt(),
                    category = (m["category"] as Number).toInt(),
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
                        else -> error("unexpected token key type: $k")
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
        return if (hasRulesFields) {
            Fixture.Full(
                identity = identity,
                rules = Fixture.Full.Rules(
                    power = raw["power"] as String? ?: "",
                    toughness = raw["toughness"] as String? ?: "",
                    colors = (raw["colors"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    types = (raw["types"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    subtypes = (raw["subtypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    supertypes = (raw["supertypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
                    manaCost = parseManaCost(raw["manaCost"] as String? ?: ""),
                ),
            )
        } else {
            Fixture.Slim(identity)
        }
    }
}
