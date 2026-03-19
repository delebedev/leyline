package leyline.frontdoor

/**
 * Static protocol data loaded from classpath resources (`fd-golden/`).
 *
 * Formats, sets, graph definitions, etc. are read once and reused for every
 * FD connection. Player-specific data (decks, preferences) is served from
 * the repository layer; this class provides the fallback values.
 */
class GoldenData(
    val getFormatsProto: ByteArray,
    val getSetsProto: ByteArray,
    val graphDefinitionsJson: String,
    val designerMetadataJson: String,
    val graphStateResponses: Map<String, String>,
    val preconDecksJson: String,
) {
    companion object {
        fun loadFromClasspath(): GoldenData = GoldenData(
            // Format and set definitions — built from hand-written JSON data via protobuf-java.
            // Functional interoperability data (set codes, format names, collation IDs).
            getFormatsProto = FdProtoBuilder.buildFormatsProto(),
            getSetsProto = FdProtoBuilder.buildSetsProto(),
            graphDefinitionsJson = loadTextResource("fd-golden/graph-definitions.json"),
            designerMetadataJson = loadTextResource("fd-golden/designer-metadata.json"),
            graphStateResponses = mapOf(
                "NPE_Tutorial" to loadTextResource("fd-golden/graph-state-npe-tutorial.json"),
                "NewPlayerExperience" to loadTextResource("fd-golden/graph-state-npe.json"),
                "ColorChallenge" to loadTextResource("fd-golden/graph-state-color-challenge.json"),
            ),
            preconDecksJson = loadTextResource("fd-golden/precon-decks.json"),
        )

        private fun loadResource(path: String): ByteArray =
            GoldenData::class.java.classLoader.getResourceAsStream(path)
                ?.readBytes()
                ?: error("Missing classpath resource: $path")

        private fun loadTextResource(path: String): String =
            loadResource(path).toString(Charsets.UTF_8)
    }
}
