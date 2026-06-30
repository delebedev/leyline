package leyline.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import wotc.mtgo.gre.external.messaging.Messages.CardColor
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SubType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun Route.installCardRoutes(services: WebServices) {
    route("/cards") {
        get("/search") {
            val query = call.requiredQuery("q")
            require(query.length >= 2) { "query too short (min 2 chars)" }
            val colors =
                call.request.queryParameters["colors"]
                    ?.uppercase()
                    ?.split(",")
                    ?.filter { it in WUBRG }
                    .orEmpty()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

            call.respond(searchCards(services.cardRepository, query, colors, limit))
        }
        post("/parse-decklist") {
            val request = call.receive<ParseDecklistRequest>()
            require(request.text.isNotBlank()) { "empty decklist" }
            require(request.text.length <= MAX_DECKLIST_CHARS) { "decklist too large (max $MAX_DECKLIST_CHARS chars)" }
            call.respond(parseDecklist(services.cardRepository, request.text))
        }
    }
}

internal fun Route.installPublicCardRoutes(services: WebServices) {
    get("/public/cards/by-grpids") {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respond(cardMetadataByGrpIds(services.cardRepository, call.request.queryParameters["ids"]))
    }
}

private fun cardMetadataByGrpIds(
    cardRepository: CardRepository,
    rawIds: String?,
): Map<Int, GreCardMetaDto> {
    val ids = rawIds?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.distinct()
    require(!ids.isNullOrEmpty()) { "ids is required" }
    return ids.associateWith { grpId -> cardRepository.cardMeta(grpId) }
}

private fun searchCards(
    cardRepository: CardRepository,
    query: String,
    colors: List<String>,
    limit: Int,
): List<DraftCardDto> {
    val queryLower = query.lowercase()
    return cardRepository
        .findAllGrpIds()
        .asSequence()
        .mapNotNull { grpId -> cardRepository.cardMeta(grpId).takeIf { it.name?.lowercase()?.contains(queryLower) == true } }
        .filter { meta -> colors.isEmpty() || cardRepository.findByGrpId(meta.grpId)?.colors?.any { it.toColorSymbol() in colors } == true }
        .distinctBy { it.name?.lowercase() }
        .take(limit)
        .map { it.toDraftCard() }
        .toList()
}

private val LINE_REGEX = Regex("""^(\d+)\s+(.+)$""")
private val SET_PAREN_REGEX = Regex("""\s*\([A-Za-z0-9]+\)\s*\d*\s*$""")
private val PIPE_SET_REGEX = Regex("""\|[A-Za-z0-9]+$""")
private val SECTION_BRACKET_REGEX = Regex("""^\[(\w+)]$""")
private val SECTION_LABEL_REGEX = Regex("""^(Sideboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)
private val COMMENT_REGEX = Regex("""^[#;]|^//""")
private val SET_CODE_REGEX = Regex("""\(([A-Za-z0-9]+)\)""")
private val WUBRG = setOf("W", "U", "B", "R", "G")

/** Upper bound on decklist text size — a generous Commander list is well under this. */
private const val MAX_DECKLIST_CHARS = 20_000

private fun parseDecklist(
    cardRepository: CardRepository,
    text: String,
): ParseDecklistResponse {
    val mainboard = mutableListOf<ParsedCardDto>()
    val sideboard = mutableListOf<ParsedCardDto>()
    val commander = mutableListOf<ParsedCardDto>()
    val errors = mutableListOf<String>()
    var currentSection = "main"

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isBlank() || COMMENT_REGEX.containsMatchIn(line)) continue

        SECTION_BRACKET_REGEX.matchEntire(line)?.let {
            currentSection = it.groupValues[1].lowercase()
            continue
        }
        SECTION_LABEL_REGEX.matchEntire(line)?.let {
            currentSection = it.groupValues[1].lowercase()
            continue
        }

        val match = LINE_REGEX.matchEntire(line)
        val quantity = match?.groupValues?.get(1)?.toInt() ?: 1
        val rawName = match?.groupValues?.get(2) ?: line
        val cleaned = rawName.replace(SET_PAREN_REGEX, "").replace(PIPE_SET_REGEX, "").trim()
        if (cleaned.isBlank()) continue

        val setCode = SET_CODE_REGEX.find(rawName)?.groupValues?.get(1)
        val grpId = cardRepository.findGrpIdByNameAndSet(cleaned, setCode.orEmpty()) ?: cardRepository.findGrpIdByName(cleaned)
        val parsedCard =
            if (grpId != null) {
                val meta = cardRepository.cardMeta(grpId)
                ParsedCardDto(name = meta.name ?: cleaned, grpId = grpId, quantity = quantity, found = true, card = meta.toDraftCard())
            } else {
                errors.add("Card not found: $cleaned")
                ParsedCardDto(name = cleaned, quantity = quantity, found = false)
            }

        when (currentSection) {
            "sideboard" -> sideboard.add(parsedCard)
            "commander", "companion" -> commander.add(parsedCard)
            else -> mainboard.add(parsedCard)
        }
    }

    return ParseDecklistResponse(mainboard = mainboard, sideboard = sideboard, commander = commander, errors = errors)
}

private fun CardRepository.cardMeta(grpId: Int): GreCardMetaDto {
    val data = findByGrpId(grpId)
    val name = findNameByGrpId(grpId)
    return GreCardMetaDto(
        grpId = grpId,
        name = name,
        titleId = data?.titleId,
        manaCost = data?.manaCost?.toDisplayManaCost(),
        power = data?.power?.takeIf { it.isNotBlank() },
        toughness = data?.toughness?.takeIf { it.isNotBlank() },
        types = data?.typeLine(),
        subtypes = data?.subtypeLine(),
        imageUrl = name?.scryfallImageUrl(),
    )
}

private fun GreCardMetaDto.toDraftCard(): DraftCardDto =
    DraftCardDto(
        name = name ?: "Card $grpId",
        grpId = grpId,
        manaCost = manaCost,
        typeLine = listOfNotNull(types, subtypes).joinToString(" — ").takeIf { it.isNotBlank() },
        rarity = null,
        colors = manaCost.toColorSymbols(),
        setCode = setCode,
        collectorNumber = null,
    )

private fun CardData.typeLine(): String? =
    types
        .mapNotNull { CardType.forNumber(it)?.displayName() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

private fun CardData.subtypeLine(): String? =
    subtypes
        .mapNotNull { SubType.forNumber(it)?.displayName() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

private fun List<Pair<ManaColor, Int>>.toDisplayManaCost(): String? =
    flatMap { (color, count) -> color.toManaSymbols(count) }.joinToString("").takeIf { it.isNotBlank() }

private fun ManaColor.toManaSymbols(count: Int): List<String> =
    when (this) {
        ManaColor.Generic -> listOf("{$count}")
        ManaColor.White_afc9 -> List(count) { "{W}" }
        ManaColor.Blue_afc9 -> List(count) { "{U}" }
        ManaColor.Black_afc9 -> List(count) { "{B}" }
        ManaColor.Red_afc9 -> List(count) { "{R}" }
        ManaColor.Green_afc9 -> List(count) { "{G}" }
        ManaColor.X -> List(count) { "{X}" }
        ManaColor.Colorless_afc9 -> List(count) { "{C}" }
        ManaColor.Snow_afc9 -> List(count) { "{S}" }
        ManaColor.TwoGeneric -> List(count) { "{2}" }
        ManaColor.None_afc9,
        ManaColor.Phyrexian_afc9,
        ManaColor.Y,
        ManaColor.AnyColor,
        ManaColor.UNRECOGNIZED,
        -> emptyList()
    }

private fun String?.toColorSymbols(): List<String> =
    orEmpty()
        .split("{")
        .mapNotNull { it.substringBefore("}").takeIf { symbol -> symbol in WUBRG } }
        .distinct()

private fun Int.toColorSymbol(): String? =
    when (CardColor.forNumber(this)) {
        CardColor.White_a3b0 -> "W"
        CardColor.Blue_a3b0 -> "U"
        CardColor.Black_a3b0 -> "B"
        CardColor.Red_a3b0 -> "R"
        CardColor.Green_a3b0 -> "G"
        CardColor.Colorless_a3b0,
        CardColor.Land_a3b0,
        CardColor.Artifact_a3b0,
        CardColor.UNRECOGNIZED,
        -> null
    }

private fun Enum<*>.displayName(): String? =
    name
        .takeUnless { it == "UNRECOGNIZED" || it.startsWith("None_") }
        ?.replace(Regex("_[a-z0-9]+$"), "")

private fun String.scryfallImageUrl(): String {
    val encoded = URLEncoder.encode(this, StandardCharsets.UTF_8)
    return "https://api.scryfall.com/cards/named?exact=$encoded&format=image&version=normal"
}
