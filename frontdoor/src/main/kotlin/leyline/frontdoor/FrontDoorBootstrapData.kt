package leyline.frontdoor

/**
 * Static bootstrap protocol data loaded from classpath resources (`fd-bootstrap/`).
 *
 * Formats, sets, graph definitions, etc. are read once and reused for every
 * FD connection. Player-specific data (decks, preferences) is served from
 * the repository layer; this class provides the fallback values.
 */
class FrontDoorBootstrapData(
    val getFormatsProto: ByteArray,
    val getSetsProto: ByteArray,
    val graphDefinitionsJson: String,
    val designerMetadataJson: String,
    val graphStateResponses: Map<String, String>,
    val preconDecksJson: String,
) {
    companion object {
        fun loadFromClasspath(): FrontDoorBootstrapData = FrontDoorBootstrapData(
            // Format and set definitions — built from hand-written JSON data via protobuf-java.
            getFormatsProto = FdProtoBuilder.buildFormatsProto(),
            getSetsProto = FdProtoBuilder.buildSetsProto(),
            graphDefinitionsJson = loadTextResource("fd-bootstrap/graph-definitions.json"),
            designerMetadataJson = loadTextResource("fd-bootstrap/designer-metadata.json"),
            graphStateResponses = mapOf(
                "NPE_Tutorial" to loadTextResource("fd-bootstrap/graph-state-npe-tutorial.json"),
                "NewPlayerExperience" to loadTextResource("fd-bootstrap/graph-state-npe.json"),
                // CampaignGraphManager is schema-sensitive; keep matching state entries present.
                "ColorChallenge" to loadTextResource("fd-bootstrap/graph-state-color-challenge.json"),
            ),
            preconDecksJson = EMPTY_PRECON_DECKS_JSON,
        )

        private const val EMPTY_PRECON_DECKS_JSON = """{"CacheVersion":65407475,"PreconDecks":[]}"""

        private fun loadResource(path: String): ByteArray =
            FrontDoorBootstrapData::class.java.classLoader.getResourceAsStream(path)
                ?.readBytes()
                ?: error("Missing classpath resource: $path")

        private fun loadTextResource(path: String): String =
            loadResource(path).toString(Charsets.UTF_8)
    }
}
