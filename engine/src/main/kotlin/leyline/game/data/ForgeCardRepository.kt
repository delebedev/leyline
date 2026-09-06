package leyline.game.data

import forge.StaticData
import forge.card.CardRules
import forge.card.ICardFace
import forge.localinstance.properties.ForgeConstants
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.manaTokenToPair
import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperType
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat

class UnsupportedCardDefinitionException(
    message: String,
) : IllegalArgumentException(message)

/** Forge-backed card metadata with catalog-scoped GRE identities. */
class ForgeCardRepository private constructor(
    private val cardIndexByName: Map<String, Int>,
    internal val catalogIdentityIds: Map<String, Int>,
    private val faceAliases: Map<String, List<FaceAlias>>,
    val catalogVersion: String,
) : CardRepository {
    private val rows = InMemoryCardRepository()
    private val loading = mutableSetOf<String>()
    private val tokens = mutableMapOf<String, MutableSet<Int>>()
    private val primaryIds = cardIndexByName.values.map { CARD_BASE + it }.toSet()
    private val primaryNameById = cardIndexByName.entries.associate { (name, index) -> CARD_BASE + index to name }
    private val faceAliasById =
        faceAliases.values.flatten().associateBy { alias -> catalogIdentityIds.getValue(alias.identityKey) }
    internal val identityKeys = primaryNameById.mapValuesTo(linkedMapOf()) { (_, name) -> "card:$name" }
    private val keywordBases =
        KeywordAbilityIds::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { normalize(it.name) to it.getInt(null) }

    companion object {
        private const val CARD_BASE = 200_000_000
        private const val DERIVED_ID_BASE = 300_000_000
        private const val IDENTITY_SCHEME = "forge-card-catalog-v2-dense-semantic-keys"

        private data class CatalogDescriptor(
            val indexes: Map<String, Int>,
            val identityIds: Map<String, Int>,
            val faceAliases: Map<String, List<FaceAlias>>,
            val version: String,
        )

        private val descriptor: CatalogDescriptor by lazy {
            GameBootstrap.initializeCardDatabase(quiet = true)
            val names =
                StaticData
                    .instance()
                    .commonCards.uniqueCards
                    .map { it.name }
                    .distinct()
                    .sorted()
            val keys = names.flatMap(::definitionKeys).distinct().sorted()
            val identityIds = keys.withIndex().associate { it.value to DERIVED_ID_BASE + it.index }
            require(DERIVED_ID_BASE.toLong() + keys.size <= Int.MAX_VALUE) { "Card catalog exceeds the GRE identity range" }
            CatalogDescriptor(
                names.withIndex().associate { it.value to it.index },
                identityIds,
                names.flatMap(::faceAliases).groupBy { it.name },
                definitionVersion(names),
            )
        }

        fun open(): ForgeCardRepository =
            ForgeCardRepository(descriptor.indexes, descriptor.identityIds, descriptor.faceAliases, descriptor.version)

        private fun faceAliases(name: String): List<FaceAlias> {
            val rules = requireNotNull(StaticData.instance().commonCards.getCard(name)).rules
            if (rules.splitType.name in setOf("Split", "Specialize")) return emptyList()
            return rules.allFaces.drop(1).mapIndexed { offset, face ->
                val index = offset + 1
                FaceAlias(face.name, rules.name, "face:${rules.name}:$index:${face.name}")
            }
        }

        private fun definitionKeys(name: String): List<String> {
            val rules = requireNotNull(StaticData.instance().commonCards.getCard(name)).rules
            if (rules.splitType.name in setOf("Split", "Specialize")) return emptyList()
            return buildList {
                rules.allFaces.forEachIndexed { faceIndex, face ->
                    if (faceIndex > 0) add("face:${rules.name}:$faceIndex:${face.name}")
                    addFaceKeys(face, "${rules.name}:face:$faceIndex")
                }
                rules.tokens.distinct().forEachIndexed { tokenIndex, script ->
                    val token = StaticData.instance().allTokens.getToken(script) ?: return@forEachIndexed
                    add("token:${rules.name}:$tokenIndex:$script")
                    addFaceKeys(token.rules.mainPart, "${rules.name}:token:$tokenIndex:$script")
                    add("${rules.name}:token-source:$tokenIndex:$script")
                }
            }
        }

        private fun MutableList<String>.addFaceKeys(
            face: ICardFace,
            prefix: String,
        ) {
            var slot = 0

            fun addRows(rows: Iterable<String>) {
                rows.forEach { raw ->
                    add("$prefix:ability:${slot++}:$raw")
                    val variables = face.variables.associate { it.key to it.value }
                    val parsed = parseParams(raw)
                    val effect = parsed["Execute"]?.let(variables::get)?.let(::parseParams) ?: parsed
                    effect["Choices"]?.split(',')?.forEachIndexed { index, variable ->
                        add("$prefix:mode:${slot - 1}:$index:$variable")
                    }
                }
            }
            addRows(face.keywords)
            addRows(face.abilities)
            addRows(face.triggers)
            addRows(face.staticAbilities)
            addRows(face.replacements)
        }

        private fun definitionVersion(names: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")

            fun frame(value: ByteArray) {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
                digest.update(value)
            }
            frame(IDENTITY_SCHEME.toByteArray())
            names.forEach { frame(it.toByteArray()) }
            listOf(ForgeConstants.CARD_DATA_DIR, ForgeConstants.TOKEN_DATA_DIR)
                .map(Paths::get)
                .forEach { root ->
                    require(Files.isDirectory(root)) { "Forge definition directory is unavailable: $root" }
                    frame(root.fileName.toString().toByteArray())
                    Files.walk(root).use { paths ->
                        paths.filter(Files::isRegularFile).sorted().forEach { path ->
                            frame(root.relativize(path).toString().toByteArray())
                            frame(Files.readAllBytes(path))
                        }
                    }
                }
            return HexFormat.of().formatHex(digest.digest())
        }
    }

    @Synchronized
    override fun findGrpIdByName(name: String): Int? {
        val catalogIndex = cardIndexByName[name]
        if (catalogIndex == null) {
            val alias = faceAliases[name]?.singleOrNull() ?: return null
            findGrpIdByName(alias.parentName) ?: return null
            return catalogIdentityIds[alias.identityKey]
        }
        rows.findByGrpId(CARD_BASE + catalogIndex)?.let { return it.grpId }
        if (!loading.add(name)) return null
        try {
            val data = StaticData.instance()
            val paper =
                data.commonCards.getCard(name) ?: run {
                    data.attemptToLoadCard(name)
                    data.commonCards.getCard(name)
                } ?: return null
            registerRules(paper.rules, cardIndexByName[paper.rules.name] ?: catalogIndex)
            return rows.findGrpIdByName(name)
        } finally {
            loading.remove(name)
        }
    }

    private fun registerRules(
        rules: CardRules,
        catalogIndex: Int,
    ) {
        if (rules.splitType.name == "Split") throw UnsupportedCardDefinitionException("Unsupported combined card identity: ${rules.name}")
        if (rules.splitType.name == "Specialize") throw UnsupportedCardDefinitionException("Unsupported specialize identity: ${rules.name}")
        val faces = rules.allFaces
        val faceIds =
            faces.indices.map {
                if (it ==
                    0
                ) {
                    CARD_BASE + catalogIndex
                } else {
                    identityId("face:${rules.name}:$it:${faces[it].name}")
                }
            }
        faces.forEachIndexed { index, face ->
            claim(faceIds[index], if (index == 0) "card:${rules.name}" else "face:${rules.name}:$index:${face.name}")
            val linkedType =
                if (rules.splitType.name == "Adventure") {
                    if (index == 0) {
                        8
                    } else {
                        7
                    }
                } else {
                    0
                }
            registerFace(face, faceIds[index], faceIds.filter { it != faceIds[index] }, linkedType, "${rules.name}:face:$index")
        }
        rows.register(faceIds.first(), rules.name)

        val tokenIds =
            rules.tokens
                .distinct()
                .mapIndexedNotNull { index, script ->
                    val token = StaticData.instance().allTokens.getToken(script) ?: return@mapIndexedNotNull null
                    val tokenId = identityId("token:${rules.name}:$index:$script")
                    registerFace(
                        token.rules.mainPart,
                        tokenId,
                        emptyList(),
                        0,
                        "${rules.name}:token:$index:$script",
                    )
                    val tokenName = token.rules.mainPart.name
                    tokens.getOrPut(tokenName) { mutableSetOf() }.add(tokenId)
                    tokens.getOrPut(tokenName.removeSuffix(" Token")) { mutableSetOf() }.add(tokenId)
                    identityId("${rules.name}:token-source:$index:$script") to tokenId
                }.toMap()
        faces.forEachIndexed { index, face ->
            val row = requireNotNull(rows.findByGrpId(faceIds[index]))
            rows.registerData(row.copy(tokenGrpIds = tokenIds), face.name)
        }
        rows.register(faceIds.first(), rules.name)
    }

    private fun registerFace(
        face: ICardFace,
        cardId: Int,
        linked: List<Int>,
        linkedType: Int,
        identityPrefix: String,
    ) {
        val abilities = mutableListOf<Pair<Int, Int>>()
        val kinds = mutableListOf<SlotKind>()
        val categories = mutableListOf<Int>()
        val variables = face.variables.associate { it.key to it.value }

        var semanticSlot = 0

        fun addRow(
            raw: String,
            category: Int,
            kind: SlotKind,
            base: Int = 0,
            cost: String = "",
        ): Int {
            val keySlot = semanticSlot++
            val id = identityId("$identityPrefix:ability:$keySlot:$raw")
            val mana = cost.split(Regex("\\s+")).mapNotNull(::manaTokenToPair)
            abilities += id to id
            kinds += kind
            categories += category
            rows.registerAbilityInfo(id, AbilityInfo(base, mana, category, if (kind == SlotKind.Mana) 1 else 0))
            val parsed = params(raw)
            val text = parsed["SpellDescription"] ?: parsed["TriggerDescription"] ?: parsed["Description"] ?: raw
            rows.registerAbilityLocalization(id, AbilityLocalization(text, mana))
            val effect = parsed["Execute"]?.let(variables::get)?.let(::params) ?: parsed
            effect["Choices"]
                ?.split(",")
                ?.mapIndexed { index, variable ->
                    val child = identityId("$identityPrefix:mode:$keySlot:$index:$variable")
                    val description = variables[variable]?.let(::params)?.get("SpellDescription") ?: variable
                    rows.registerAbilityLocalization(child, AbilityLocalization(description))
                    child
                }?.let { rows.registerModalOptions(cardId, ModalAbilityInfo(id, it)) }
            return id
        }

        face.keywords.forEach { keyword ->
            val parts = keyword.split(":")
            addRow(keyword, 8, SlotKind.Keyword, keywordBases[normalize(parts.first())] ?: 0, parts.getOrElse(1) { "" })
        }
        face.abilities.forEach { raw ->
            val parsed = params(raw)
            val activated = parsed.containsKey("AB")
            val mana = parsed["AB"] in listOf("Mana", "ManaReflected")
            addRow(
                raw,
                if (activated) 1 else 4,
                if (mana) {
                    SlotKind.Mana
                } else if (activated) {
                    SlotKind.Activated
                } else {
                    SlotKind.Intrinsic
                },
                cost = parsed["Cost"].orEmpty(),
            )
        }
        face.triggers.forEach { raw -> addRow(raw, 2, SlotKind.Intrinsic) }
        face.staticAbilities.forEach { raw -> addRow(raw, 3, SlotKind.Intrinsic) }
        face.replacements.forEach { raw -> addRow(raw, 3, SlotKind.Intrinsic) }
        if (face.type.isBasicLand) {
            BasicLandAbilities.byForgeSubtypeNames(face.type.subtypes)?.let { id ->
                abilities += id to id
                kinds += SlotKind.Mana
                categories += 1
            }
        }
        val colors =
            listOf(
                1 to face.color.hasWhite(),
                2 to face.color.hasBlue(),
                3 to face.color.hasBlack(),
                4 to face.color.hasRed(),
                5 to face.color.hasGreen(),
            ).filter { it.second }
                .map { it.first }
        val typeNames = face.type.coreTypes.map { it.name }
        val subtypeNames = face.type.subtypes.toList()
        rows.registerData(
            CardData(
                grpId = cardId,
                titleId = cardId,
                power = face.power.orEmpty(),
                toughness = face.toughness.orEmpty(),
                colors = colors,
                types = enums(typeNames, CardType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                subtypes = enums(subtypeNames, SubType.values().filter { it.name != "UNRECOGNIZED" }.map { it.name to it.number }),
                supertypes =
                    enums(
                        face.type.supertypes.map { it.name },
                        SuperType.values().filter { it.name != "UNRECOGNIZED" }.map {
                            it.name to
                                it.number
                        },
                    ),
                abilityIds = abilities,
                abilityKinds = kinds,
                abilityCategories = categories,
                manaCost = ManaColorMapping.deriveManaCost(face.manaCost),
                linkedFaceType = linkedType,
                linkedFaceGrpIds = linked,
                typeNames = typeNames,
                subtypeNames = subtypeNames,
                keywordNames = face.keywords.map { it.substringBefore(':').replace('_', ' ') },
            ),
            face.name,
        )
    }

    private fun identityId(key: String): Int =
        requireNotNull(catalogIdentityIds[key]) {
            "Missing catalog identity: $key"
        }.also { claim(it, key) }

    private fun claim(
        id: Int,
        key: String,
    ) {
        val previous = identityKeys.putIfAbsent(id, key)
        check(previous == null || previous == key) { "Card identity collision: $previous and $key" }
    }

    private fun enums(
        names: List<String>,
        values: List<Pair<String, Int>>,
    ): List<Int> = names.mapNotNull { name -> values.firstOrNull { normalize(it.first.substringBefore('_')) == normalize(name) }?.second }

    private fun params(raw: String): Map<String, String> =
        raw
            .split('|')
            .mapNotNull {
                val parts = it.split('$', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

    @Synchronized
    override fun findByGrpId(grpId: Int): CardData? {
        rows.findByGrpId(grpId)?.let { return it }
        primaryNameById[grpId]?.let(::findGrpIdByName)
        faceAliasById[grpId]?.let { findGrpIdByName(it.parentName) }
        return rows.findByGrpId(grpId)
    }

    @Synchronized
    override fun findNameByGrpId(grpId: Int): String? = rows.findNameByGrpId(grpId) ?: primaryNameById[grpId] ?: faceAliasById[grpId]?.name

    override fun findGrpIdByNameAnyFace(name: String): Int? = findGrpIdByName(name)

    @Synchronized
    override fun findTokenGrpIdByName(name: String): Int? = (tokens[name] ?: tokens[name.removeSuffix(" Token")])?.singleOrNull()

    override fun findAllGrpIds(): List<Int> = primaryIds.toList()

    @Synchronized
    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = rows.lookupModalOptions(cardGrpId)

    @Synchronized
    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = rows.findAbilityInfo(abilityGrpId)

    @Synchronized
    override fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? = rows.findAbilityLocalization(abilityGrpId)
}

private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

private data class FaceAlias(
    val name: String,
    val parentName: String,
    val identityKey: String,
)

private fun parseParams(raw: String): Map<String, String> =
    raw
        .split('|')
        .mapNotNull {
            val parts = it.split('$', limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
