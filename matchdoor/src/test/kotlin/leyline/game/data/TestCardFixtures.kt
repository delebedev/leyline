package leyline.game.data

import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Per-card YAML fixture loader. Reads each YAML file under the test resources
 * directory `test-cards` and exposes a [CardRepository] populated with the
 * fixture data — no Arena SQLite required.
 *
 * Schema mirrors the narrow subset of fields that [ExposedCardRepository]
 * reads (grpId, titleId, mana, types, ability ids plus Category and
 * ModalChildIds, linked faces, produced tokens). Self-contained for tests —
 * every grpId referenced in tokens or linkedFaces must have its own YAML in
 * the same directory.
 */
object TestCardFixtures {
    private const val DEFAULT_RESOURCE_DIR = "test-cards"

    /**
     * Load every YAML fixture in the test resources `test-cards/` directory
     * into a fresh [InMemoryCardRepository]. Most tests want this.
     */
    fun repository(): InMemoryCardRepository {
        val repo = InMemoryCardRepository()
        for (entry in loadAllEntries()) entry.applyTo(repo)
        return repo
    }

    /**
     * Register a single named card (and its closure: linked faces and produced
     * tokens) into [repo]. Errors if the named card isn't in the fixture set.
     */
    fun register(repo: InMemoryCardRepository, cardName: String) {
        val all = loadAllEntries().associateBy { it.grpId }
        val byName = all.values.associateBy { it.name }
        val root = byName[cardName]
            ?: error("No fixture found for card '$cardName' under $DEFAULT_RESOURCE_DIR/")
        val toApply = mutableSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(root.grpId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!toApply.add(id)) continue
            val entry = all[id] ?: continue
            stack.addAll(entry.linkedFaces)
            stack.addAll(entry.tokens.values)
        }
        for (id in toApply) all[id]?.applyTo(repo)
    }

    private fun loadAllEntries(): List<Entry> {
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
    private fun parseFile(path: Path): Entry {
        val raw = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
            ?: error("Empty fixture: $path")
        return Entry(
            name = raw["name"] as String,
            grpId = (raw["grpId"] as Number).toInt(),
            titleId = (raw["titleId"] as Number).toInt(),
            power = raw["power"] as String? ?: "",
            toughness = raw["toughness"] as String? ?: "",
            colors = (raw["colors"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
            types = (raw["types"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
            subtypes = (raw["subtypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
            supertypes = (raw["supertypes"] as List<*>? ?: emptyList<Any>()).map { (it as Number).toInt() },
            manaCost = parseManaCost(raw["manaCost"] as String? ?: ""),
            abilities = (raw["abilities"] as List<*>? ?: emptyList<Any>()).map { entry ->
                val m = entry as Map<String, Any?>
                AbilityEntry(
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
                    // YAML int keys round-trip as String when parsed as Map<String, Any>; coerce.
                    val key = when (k) {
                        is Number -> k.toInt()
                        is String -> k.toInt()
                        else -> error("unexpected token key type: $k")
                    }
                    key to (v as Number).toInt()
                },
            linkedFaces = (raw["linkedFaces"] as List<*>? ?: emptyList<Any>())
                .map { (it as Number).toInt() },
        )
    }

    private data class Entry(
        val name: String,
        val grpId: Int,
        val titleId: Int,
        val power: String,
        val toughness: String,
        val colors: List<Int>,
        val types: List<Int>,
        val subtypes: List<Int>,
        val supertypes: List<Int>,
        val manaCost: List<Pair<wotc.mtgo.gre.external.messaging.Messages.ManaColor, Int>>,
        val abilities: List<AbilityEntry>,
        val tokens: Map<Int, Int>,
        val linkedFaces: List<Int>,
    ) {
        fun applyTo(repo: InMemoryCardRepository) {
            val abilityIds = abilities.map { it.id to it.textId }
            val abilityKinds = abilities.map { ab ->
                if (ab.category == 1) SlotKind.Activated else SlotKind.Intrinsic
            }
            val data = CardData(
                grpId = grpId,
                titleId = titleId,
                power = power,
                toughness = toughness,
                colors = colors,
                types = types,
                subtypes = subtypes,
                supertypes = supertypes,
                abilityIds = abilityIds,
                abilityKinds = abilityKinds,
                manaCost = manaCost,
                tokenGrpIds = tokens,
                linkedFaceGrpIds = linkedFaces,
            )
            repo.registerData(data, name)
            for (ab in abilities) {
                if (ab.baseId != 0 || ab.activationMana.isNotEmpty()) {
                    repo.registerAbilityInfo(ab.id, AbilityInfo(ab.baseId, ab.activationMana))
                }
                if (ab.modalChildren.isNotEmpty()) {
                    repo.registerModalOptions(grpId, ModalAbilityInfo(ab.id, ab.modalChildren))
                }
            }
        }
    }

    private data class AbilityEntry(
        val id: Int,
        val textId: Int,
        val category: Int,
        val baseId: Int,
        val activationMana: List<Pair<wotc.mtgo.gre.external.messaging.Messages.ManaColor, Int>>,
        val modalChildren: List<Int>,
    )
}
